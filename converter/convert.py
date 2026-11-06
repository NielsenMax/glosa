"""Convert an OCR'd book scan (PDF with a text layer) into a reflowable EPUB 3.

Usage: python -I convert.py <book.pdf> <profile.json> <out.epub>

The profile carries what the scan cannot tell reliably: metadata, which pages to keep,
and where each chapter or part starts. See profiles/ for an example.
"""
import collections
import html
import json
import re
import sys
import uuid
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone

import pymupdf

CAPTION = re.compile(r"^(TABLE|FIGURE)\s+\d+\s*-\s*\d+")
NUMBERED_HEADING = re.compile(r"^(\d{1,2}(?:\.\d{1,2}){1,3})\s+\S")
LIST_MARKER = re.compile(r"^(•|·|■|\d{1,2}\.)\s")
BIB_SUBHEADING = re.compile(r"^(Chapter \d+:|General Works)")
BIB_ENTRY = re.compile(r"^[A-Z][^\s,]+(?: [A-Z][^\s,]+)?, [A-Z]")

BAND_GAP = 11
TEXT_SIZE_RANGE = (7.3, 12.6)
CROP_ZOOM = 2.6


@dataclass
class Profile:
    id: str
    title: str
    subtitle: str
    author: str
    publisher: str
    year: str
    language: str
    cover_page: int
    page_ranges: list
    hanging_indent_from_page: int
    sections: dict

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        raw["sections"] = {int(page): tuple(entry) for page, entry in raw["sections"].items()}
        return cls(**raw)

    def pages(self):
        for first, last in self.page_ranges:
            yield from range(first, last + 1)


@dataclass
class Run:
    text: str
    italic: bool
    bold: bool
    size: float = 10


@dataclass
class Line:
    runs: list
    x0: float
    y0: float
    x1: float
    y1: float
    size: float
    sans: bool
    bold: bool

    @property
    def text(self):
        return "".join(r.text for r in self.runs).strip()


@dataclass
class Row:
    lines: list
    is_columnar: bool = False

    @property
    def x0(self): return min(l.x0 for l in self.lines)
    @property
    def x1(self): return max(l.x1 for l in self.lines)
    @property
    def y0(self): return min(l.y0 for l in self.lines)
    @property
    def y1(self): return max(l.y1 for l in self.lines)
    @property
    def text(self): return " ".join(l.text for l in self.lines)
    @property
    def size(self): return max(l.size for l in self.lines)
    @property
    def sans(self): return self.lines[0].sans
    @property
    def bold(self): return all(l.bold for l in self.lines)

    @property
    def runs(self):
        runs = []
        for line in self.lines:
            if runs:
                runs.append(Run(" ", False, False))
            runs.extend(line.runs)
        return runs

    @property
    def is_tabular(self):
        text = self.text
        letters = sum(c.isalpha() for c in text)
        return (
            self.is_columnar
            or "\t" in text
            or not TEXT_SIZE_RANGE[0] <= self.size <= TEXT_SIZE_RANGE[1]
            or letters / max(len(text), 1) < 0.45
        )


@dataclass
class Band:
    rows: list
    kind: str = "text"

    @property
    def y0(self): return min(r.y0 for r in self.rows)
    @property
    def y1(self): return max(r.y1 for r in self.rows)
    @property
    def x0(self): return min(r.x0 for r in self.rows)
    @property
    def x1(self): return max(r.x1 for r in self.rows)


@dataclass
class Column:
    left: float
    right: float
    is_ragged: bool


@dataclass
class Block:
    kind: str
    runs: list = field(default_factory=list)
    level: int = 0
    image: str = ""
    children: list = field(default_factory=list)


@dataclass
class Section:
    title: str
    kind: str
    blocks: list = field(default_factory=list)


def read_lines(page):
    lines = []
    for block in page.get_text("dict")["blocks"]:
        for raw in block.get("lines", []):
            spans = [s for s in raw["spans"] if s["text"].strip() or s["text"] == " "]
            if not spans or any("Arial-BoldItalic" in s["font"] for s in spans):
                continue
            runs = [
                Run(s["text"], bool(s["flags"] & 2) or "Italic" in s["font"] or "Oblique" in s["font"],
                    bool(s["flags"] & 16) or "Bold" in s["font"], s["size"])
                for s in spans
            ]
            weights = [(len(s["text"].strip()), s) for s in spans]
            sized = [(w, s["size"]) for w, s in weights if w >= 3] or [(w, s["size"]) for w, s in weights]
            total = sum(w for w, _ in sized) or 1
            size = sum(w * sz for w, sz in sized) / total
            chars = sum(w for w, _ in weights) or 1
            sans = sum(w for w, s in weights if "Helvetica" in s["font"] or "Arial" in s["font"]) / chars > 0.5
            bold = sum(len(r.text.strip()) for r in runs if r.bold) / chars > 0.6
            x0, y0, x1, y1 = raw["bbox"]
            line = Line(runs, x0, y0, x1, y1, size, sans, bold)
            if line.text and line.text != "NEW STAMP":
                lines.append(line)
    return [l for l in lines if not is_running_header(l, page) and not is_folio(l, page)]


def is_running_header(line, page):
    return line.y0 < 42 and line.size < 9.5 and ("•" in line.text or "TRADING AND EXCHANGES" in line.text.upper())


def is_folio(line, page):
    return line.y0 > page.rect.height - 40 and re.fullmatch(r"[\divxlc]+", line.text) is not None


def main_column(lines):
    wide = [l for l in lines if l.x1 - l.x0 > 180 and not l.sans]
    wide = wide or [l for l in lines if l.x1 - l.x0 > 180]
    if len(wide) < 3:
        return None
    lefts = sorted(l.x0 for l in wide)
    rights = sorted(l.x1 for l in wide)
    return Column(lefts[len(lefts) // 10], rights[len(rights) * 9 // 10], is_ragged=False)


def group_rows(lines):
    rows = []
    for line in sorted(lines, key=lambda l: (l.y0 + l.y1) / 2):
        center = (line.y0 + line.y1) / 2
        row = rows[-1] if rows else None
        if row and row.y0 + 2 < center < row.y1 - 2:
            row.lines.append(line)
        else:
            rows.append(Row([line]))
    for row in rows:
        row.lines.sort(key=lambda l: l.x0)
        gaps = [b.x0 - a.x1 for a, b in zip(row.lines, row.lines[1:])]
        row.is_columnar = any(g > 12 for g in gaps)
    return rows


def split_bands(rows):
    bands = []
    for row in rows:
        if bands and row.y0 - bands[-1].rows[-1].y1 <= BAND_GAP and not CAPTION.match(row.text):
            bands[-1].rows.append(row)
        else:
            bands.append(Band([row]))
    return bands


def classify(bands):
    for band in bands:
        tabular = sum(r.is_tabular for r in band.rows)
        if CAPTION.match(band.rows[0].text):
            band.kind = "caption"
        elif tabular / len(band.rows) >= 0.5:
            band.kind = "float"


def ink_regions(page, column, bands):
    """Vertical spans of the column holding graphics with no OCR text over them."""
    scale = 100 / 72
    pix = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), colorspace=pymupdf.csGRAY)
    x_lo, x_hi = int(column.left * scale), min(pix.width, int(column.right * scale))
    occupied = sorted((b.y0, b.y1) for b in bands)
    regions = []
    for (_, prev_end), (next_start, _) in zip(
        [(None, 45)] + occupied, occupied + [(page.rect.height - 30, None)]
    ):
        if next_start - prev_end < 30:
            continue
        inked = []
        for y in range(int((prev_end + 3) * scale), int((next_start - 3) * scale)):
            row = pix.samples[y * pix.width + x_lo: y * pix.width + x_hi]
            if sum(1 for v in row if v < 140) >= 2:
                inked.append(y / scale)
        if inked and inked[-1] - inked[0] > 15:
            regions.append((inked[0], inked[-1]))
    return regions


@dataclass
class Region:
    column: Column
    items: list


def item_top(item):
    kind, value = item
    return value.y0 if kind == "band" else value[0]


def layout_region(page, lines, column, ink_column):
    """Bands of rows plus untexted graphics of one column, in reading order."""
    bands = split_bands(group_rows(lines))
    classify(bands)
    items = [("band", b) for b in bands]
    if ink_column:
        items += [("ink", r) for r in ink_regions(page, ink_column, bands)]
    items.sort(key=item_top)
    return Region(column, absorb_figure_labels(items))


def margin_zone(page, column):
    if column.left > 100:
        return Column(8, column.left - 6, is_ragged=True)
    return Column(column.right + 6, page.rect.width - 8, is_ragged=True)


def page_regions(page, opens_section):
    """Main and margin regions of a page, plus the drop cap opening a chapter."""
    lines = read_lines(page)
    column = main_column(lines)
    if column is None:
        column = Column(min((l.x0 for l in lines), default=30), max((l.x1 for l in lines), default=430), False)
    main, side, titles = [], [], []
    for line in lines:
        is_side = line.x1 <= column.left - 3 or line.x0 >= column.right + 3
        if opens_section and is_side and line.size >= 12.5 and (line.bold or len(line.text) <= 3):
            titles.append(line)
        elif is_side:
            side.append(line)
        else:
            main.append(line)
    if opens_section and not titles:
        top_bold = [l for l in main if l.bold and l.size >= 12.5 and l.y0 < 120]
        if top_bold:
            titles = top_bold
            main = [l for l in main if l not in top_bold]

    main, side = absorb_wide_floats(main, side)
    main, drop_cap = extract_drop_cap(main)
    main_region = layout_region(page, main, column, column)
    zone = margin_zone(page, column)
    side_column = Column(min((l.x0 for l in side), default=zone.left), zone.right, is_ragged=True)
    side_region = layout_region(page, side, side_column, None if opens_section else zone)
    side_region.items = [it for it in side_region.items if not overlaps_main_graphic(it, main_region)]
    return main_region, side_region, drop_cap


def overlaps_main_graphic(item, main_region):
    """Margin ink level with a main-column table or figure is that same wide graphic."""
    kind, value = item
    if kind != "ink":
        return False
    y0, y1 = value
    for main_kind, main_value in main_region.items:
        lo, hi = main_value if main_kind == "ink" else (main_value.y0, main_value.y1)
        if (main_kind == "ink" or main_value.kind != "text") and y0 < hi and lo < y1:
            return True
    return False


def absorb_wide_floats(main, side):
    """Wide tables and their captions spill into the margin; pull those lines back into the main column.

    Anything level with a table is part of it; just above or below, margin boxes are sans-serif
    body text, so only serif, bold or caption lines join.
    """
    for _ in range(4):
        bands = split_bands(group_rows(main))
        classify(bands)
        spans = [(b.y0, b.y1) for b in bands if b.kind in ("float", "caption")]

        def joins(line):
            center = (line.y0 + line.y1) / 2
            if any(lo <= center <= hi for lo, hi in spans):
                return True
            is_candidate = not line.sans or line.bold or CAPTION.match(line.text)
            return is_candidate and any(lo - 24 <= center <= hi + 24 for lo, hi in spans)

        spill = [l for l in side if joins(l)]
        if not spill:
            break
        main = main + spill
        side = [l for l in side if l not in spill]
    return main, side


def is_figure_page(regions):
    rows = [r for region in regions for kind, b in region.items if kind == "band" for r in b.rows]
    prose = [r for r in rows if not r.is_tabular and r.x1 - r.x0 > 150]
    return len(prose) < 3


def extract_drop_cap(lines):
    caps = [l for l in lines if len(l.text) == 1 and l.text.isalpha() and l.size > 14]
    if not caps:
        return lines, None
    cap = caps[0]
    rest = [l for l in lines if l is not cap]
    beside = [l for l in rest if l.x0 > cap.x1 - 2 and cap.y0 - 6 < l.y0 < cap.y1]
    if beside:
        first = min(beside, key=lambda l: l.y0)
        first.runs = [Run(cap.text, False, False)] + first.runs
    return rest, (cap, beside)


def absorb_figure_labels(items):
    """A short text band wedged between two graphic items is a label inside the figure."""
    def is_graphic(item):
        return item[0] == "ink" or (item[0] == "band" and item[1].kind == "float")

    for i in range(1, len(items) - 1):
        kind, band = items[i]
        if kind == "band" and band.kind == "text" and len(band.rows) <= 2:
            if is_graphic(items[i - 1]) and is_graphic(items[i + 1]):
                band.kind = "float"
    return items


class Paragraphs:
    """Builds paragraphs from rows of one text stream, across page breaks."""

    def __init__(self, hanging=False):
        self.current = None
        self.previous = None
        self.list_marker_x = None
        self.hanging = hanging

    def close(self):
        finished, self.current = self.current, None
        self.previous = None
        self.list_marker_x = None
        return finished

    def feed(self, row, column, beside_drop_cap=False):
        """Returns the paragraph this row closed, if any."""
        text = row.text
        indent = row.x0 - column.left
        marker = LIST_MARKER.match(text)
        previous_was_short = (
            self.previous is not None
            and not self.previous[1].is_ragged
            and self.previous[0].x1 < self.previous[1].right - 18
        )
        if beside_drop_cap and self.current is not None:
            starts_new = False
        elif marker:
            starts_new = True
        elif self.list_marker_x is not None:
            starts_new = row.x0 <= self.list_marker_x + 4 or indent < 6
        elif self.hanging:
            starts_new = previous_was_short or (indent < 6 and BIB_ENTRY.match(text) is not None)
        else:
            starts_new = 6 <= indent <= 30 or previous_was_short
        starts_new = starts_new or self.current is None

        closed = None
        if starts_new:
            closed = self.close() if self.current is not None else None
            self.current = Block("li" if marker else "p")
            if marker:
                self.list_marker_x = row.x0
        else:
            join_runs(self.current.runs)
        self.current.runs.extend(row.runs)
        self.previous = (row, column)
        return closed


def join_runs(runs):
    """Prepares the end of a paragraph for the next line: hyphen handling or a space."""
    if not runs:
        return
    last = runs[-1]
    last.text = last.text.rstrip()
    if last.text.endswith("—"):
        return
    if re.search(r"[A-Za-z]-$", last.text):
        last.text = last.text + "\x00"
        return
    last.text += " "


def resolve_hyphens(text, vocabulary):
    def repl(m):
        head, tail = m.group(1), m.group(2)
        joined = (head + tail).lower()
        hyphenated = f"{head}-{tail}".lower()
        if vocabulary[hyphenated] > vocabulary[joined]:
            return f"{head}-{tail}"
        return head + tail
    text = re.sub(r"(\w+)-\x00\s*(\w+)", repl, text)
    return text.replace("\x00", "")


def build_vocabulary(doc):
    vocabulary = collections.Counter()
    for page in doc:
        for word in page.get_text("words"):
            token = word[4].strip(".,;:()\"'?!”“’‘").lower()
            if token and not token.endswith("-"):
                vocabulary[token] += 1
    return vocabulary


class BoxStream:
    """Sidebar boxes of one column: body-column boxes and margin boxes run independently."""

    def __init__(self):
        self.paragraphs = Paragraphs()
        self.current = None

    def close(self):
        if self.current is not None:
            closed = self.paragraphs.close()
            if closed:
                self.current.children.append(closed)
        self.current = None


class Book:
    def __init__(self, doc, profile):
        self.doc = doc
        self.profile = profile
        self.sections = []
        self.images = {}
        self.main = Paragraphs()
        self.body_boxes = BoxStream()
        self.margin_boxes = BoxStream()
        self.pending = []
        self.vocabulary = build_vocabulary(doc)

    def section(self, title, kind):
        self.flush_main()
        self.body_boxes.close()
        self.margin_boxes.close()
        self.sections.append(Section(title, kind))

    def emit(self, block):
        self.sections[-1].blocks.append(block)

    def flush_main(self):
        closed = self.main.close()
        if closed:
            self.emit(closed)
        self.flush_pending()

    def flush_pending(self):
        for block in self.pending:
            self.emit(block)
        self.pending = []

    def defer(self, block):
        if self.main.current is None:
            self.emit(block)
        else:
            self.pending.append(block)

    def add_main_row(self, row, column, beside_drop_cap=False):
        closed = self.main.feed(row, column, beside_drop_cap)
        if closed:
            self.emit(closed)
            self.flush_pending()

    def add_box_title(self, stream, row):
        box = stream.current
        if box is not None and not box.children and stream.paragraphs.current is None:
            box.runs.append(Run(" " + row.text, False, False))
            return
        stream.close()
        stream.current = Block("box", runs=[Run(clean_box_title(row), False, False)])
        self.defer(stream.current)

    def add_box_row(self, stream, row, column):
        if row.bold and row.size >= 9 and not row.text.rstrip().endswith("."):
            self.add_box_title(stream, row)
            return
        if stream.current is None:
            stream.current = Block("box")
            self.defer(stream.current)
        closed = stream.paragraphs.feed(row, column)
        if closed:
            stream.current.children.append(closed)

    def crop(self, page, page_number, x0, y0, x1, y1):
        rect = pymupdf.Rect(x0, y0, x1, y1) & page.rect
        pix = page.get_pixmap(matrix=pymupdf.Matrix(CROP_ZOOM, CROP_ZOOM), clip=rect, colorspace=pymupdf.csGRAY)
        pix = trim_whitespace(pix)
        name = f"img/p{page_number:03d}_{len(self.images):03d}.jpg"
        self.images[name] = pix.tobytes("jpg", jpg_quality=82)
        return Block("img", image=name)

    def add_page(self, page_number):
        page = self.doc[page_number - 1]
        opens_section = page_number in self.profile.sections
        main_region, side_region, drop_cap = page_regions(page, opens_section)
        if opens_section:
            self.section(*self.profile.sections[page_number])
        self.main.hanging = page_number >= self.profile.hanging_indent_from_page
        if not opens_section and is_figure_page([main_region, side_region]):
            self.defer(self.crop(page, page_number, 0, 45, page.rect.width, page.rect.height - 35))
            return
        beside_cap = set(id(l) for l in drop_cap[1]) if drop_cap else set()
        self.add_region(page, page_number, main_region, beside_cap, is_margin=False)
        self.add_region(page, page_number, side_region, beside_cap, is_margin=True)

    def add_region(self, page, page_number, region, beside_cap, is_margin):
        column = region.column
        graphic = None
        text_bottom = 0
        for kind, item in region.items:
            if kind == "ink" or item.kind == "float":
                if kind == "ink":
                    box = (column.left, item[0], column.right, item[1])
                else:
                    box = (min(column.left, item.x0), item.y0, max(column.right, item.x1), item.y1)
                if graphic and box[1] - graphic[3] < 60:
                    graphic = (min(graphic[0], box[0]), graphic[1], max(graphic[2], box[2]), box[3])
                else:
                    self.flush_graphic(page, page_number, graphic, text_bottom, box[1])
                    graphic = box
                continue
            self.flush_graphic(page, page_number, graphic, text_bottom, item.y0)
            graphic = None
            text_bottom = item.y1
            if item.kind == "caption":
                graphic, text_bottom = self.add_caption(item, column)
            elif is_margin:
                for row in item.rows:
                    self.add_box_row(self.margin_boxes, row, column)
            else:
                self.add_text_band(item, column, beside_cap)
        self.flush_graphic(page, page_number, graphic, text_bottom, page.rect.height - 35)

    def flush_graphic(self, page, page_number, region, top_limit, bottom_limit):
        if region:
            x0, y0, x1, y1 = region
            top, bottom = max(y0 - 8, top_limit + 1), min(y1 + 8, bottom_limit - 1)
            self.defer(self.crop(page, page_number, x0 - 6, top, x1 + 6, bottom))

    def add_caption(self, band, column):
        """Defers the caption text; returns the region of a table body inside the band and the caption's bottom."""
        is_table = band.rows[0].text.startswith("TABLE")
        split = next((i for i, r in enumerate(band.rows) if i > 0 and is_table and r.is_tabular), len(band.rows))
        caption_rows, table_rows = band.rows[:split], band.rows[split:]
        label = caption_rows[0].text
        rest = " ".join(r.text for r in caption_rows[1:])
        self.defer(Block("caption", runs=[Run(label.strip(), False, True), Run(" " + rest, False, False)]))
        caption_bottom = caption_rows[-1].y1
        if not table_rows:
            return None, caption_bottom
        region = (min(column.left, min(r.x0 for r in table_rows)), table_rows[0].y0,
                  max(column.right, max(r.x1 for r in table_rows)), table_rows[-1].y1)
        return region, caption_bottom

    def add_text_band(self, band, column, beside_cap):
        rows = band.rows
        i = 0
        box_column = Column(column.left, column.right, is_ragged=True)
        while i < len(rows):
            row = rows[i]
            following = rows[i + 1] if i + 1 < len(rows) else None
            if is_run_in_heading(row, following):
                self.body_boxes.close()
                self.flush_main()
                self.emit(Block("h", runs=[Run(row.text, False, False)], level=5))
                i += 1
                continue
            if row.sans or (row.bold and following is not None and following.sans and not following.bold):
                self.add_box_row(self.body_boxes, row, box_column)
                i += 1
                continue
            self.body_boxes.close()
            heading = self.heading_level(row, is_band_start=(i == 0))
            if heading:
                runs = [Run(row.text, False, False)]
                i += 1
                while i < len(rows) and len(rows[i].text) < 60 and (
                    rows[i].bold or rows[i].text.isupper() or runs[-1].text.rstrip().endswith(":")
                ):
                    runs.append(Run(" " + rows[i].text, False, False))
                    i += 1
                self.flush_main()
                self.emit(Block("h", runs=runs, level=heading))
                continue
            self.add_main_row(row, column, beside_drop_cap=any(id(l) in beside_cap for l in row.lines))
            i += 1

    def heading_level(self, row, is_band_start):
        text = row.text
        if self.main.hanging and BIB_SUBHEADING.match(text) and row.bold:
            return 3
        m = NUMBERED_HEADING.match(text)
        if not m or not is_band_start or text.rstrip().endswith(".") or len(text) > 80:
            return 0
        return min(m.group(1).count(".") + 2, 5)

    def finish(self):
        self.flush_main()
        self.body_boxes.close()
        self.margin_boxes.close()


def is_run_in_heading(row, following):
    """Unnumbered sub-subheadings are set in short sans italic lines above serif text."""
    italic = sum(len(r.text.strip()) for r in row.runs if r.italic)
    return (
        row.sans and len(row.text) < 70 and italic > 0.6 * len(row.text.replace(" ", ""))
        and following is not None and not following.sans
    )


BOX_TITLE_JUNK = re.compile(r"^(?:[0O]{1,2},?|OP|Ok|lk|Et|Po|Dfr|peg|D|I>|1>|[^\w\s]+)\s+(?=\S)")


def clean_box_title(row):
    """The ▶ glyph opening each box comes out of OCR as a short junk word."""
    words = [r.text.strip() for r in row.runs if r.size >= 7 and r.text.strip()]
    return BOX_TITLE_JUNK.sub("", " ".join(words) or row.text)



def trim_whitespace(pix):
    w, h, samples = pix.width, pix.height, pix.samples
    dark_rows = [y for y in range(h) if min(samples[y * w:(y + 1) * w]) < 150]
    if not dark_rows:
        return pix
    top, bottom = max(0, dark_rows[0] - 8), min(h, dark_rows[-1] + 9)
    dark_cols = [x for x in range(w) if min(samples[x + top * w: x + bottom * w: w]) < 150]
    left, right = max(0, dark_cols[0] - 8), min(w, dark_cols[-1] + 9)
    rows = b"".join(samples[y * w + left: y * w + right] for y in range(top, bottom))
    return pymupdf.Pixmap(pymupdf.csGRAY, right - left, bottom - top, rows, False)


def render_runs(runs, vocabulary):
    merged = []
    for run in runs:
        if merged and (merged[-1].italic, merged[-1].bold) == (run.italic, run.bold):
            merged[-1] = Run(merged[-1].text + run.text, run.italic, run.bold)
        elif merged and not run.text.strip():
            merged[-1] = Run(merged[-1].text + run.text, merged[-1].italic, merged[-1].bold)
        else:
            merged.append(Run(run.text, run.italic, run.bold))
    text_parts = []
    for run in merged:
        text = html.escape(run.text, quote=False)
        lead = text[: len(text) - len(text.lstrip())]
        trail = text[len(text.rstrip()):]
        core = text.strip()
        if core and run.italic:
            core = f"<em>{core}</em>"
        if len(core) > 2 and run.bold:
            core = f"<strong>{core}</strong>"
        text_parts.append(lead + core + trail)
    out = resolve_hyphens("".join(text_parts), vocabulary)
    out = re.sub(r"</em>(\s*)<em>", r"\1", out)
    out = re.sub(r"([(\[“]) +", r"\1", out)
    out = re.sub(r" +([)\],;:”])", r"\1", out)
    return re.sub(r"\s+", " ", out).strip()


def strip_box_end_marker(box):
    if box.children:
        last = box.children[-1]
        if last.runs:
            last.runs[-1].text = re.sub(r"\s+(4\.1|4|◀|<|«|\||41|<1|4l)\s*$", "", last.runs[-1].text.rstrip())


def render_block(block, vocabulary):
    if block.kind == "h":
        level = block.level
        return f"<h{level}>{render_runs(block.runs, vocabulary)}</h{level}>"
    if block.kind == "img":
        return f'<div class="figure"><img src="{block.image}" alt=""/></div>'
    if block.kind == "caption":
        return f'<p class="caption">{render_runs(block.runs, vocabulary)}</p>'
    if block.kind == "box":
        strip_box_end_marker(block)
        title = render_runs(block.runs, vocabulary)
        inner = "".join(render_block(c, vocabulary) for c in block.children)
        head = f'<p class="box-title">{title}</p>' if title else ""
        return f'<aside class="box">{head}{inner}</aside>'
    css = ' class="li"' if block.kind == "li" else ""
    text = render_runs(block.runs, vocabulary)
    return f"<p{css}>{text}</p>" if text else ""


CSS = """
body { font-family: serif; line-height: 1.45; margin: 0 0.4em; }
h1 { font-size: 1.5em; margin: 1.5em 0 1em; text-align: left; page-break-before: always; }
h1.part { text-align: center; margin-top: 30%; }
h2 { font-size: 1.15em; margin: 1.6em 0 0.6em; }
h3 { font-size: 1.05em; margin: 1.3em 0 0.5em; }
h4, h5 { font-size: 1em; font-style: italic; margin: 1.1em 0 0.4em; }
p { margin: 0; text-indent: 1.3em; text-align: justify; }
h1 + p, h2 + p, h3 + p, h4 + p, h5 + p, .figure + p, .caption + p, aside + p { text-indent: 0; }
p.li { text-indent: -1.2em; margin-left: 1.2em; margin-top: 0.3em; }
p.caption { text-indent: 0; font-size: 0.85em; margin: 0.4em 0 1em; text-align: left; }
.figure { text-align: center; margin: 1em 0 0.5em; }
.figure img { max-width: 100%; }
aside.box { font-family: sans-serif; font-size: 0.9em; border-left: 3px solid #888; padding: 0.3em 0 0.3em 0.8em; margin: 1.2em 0; }
aside.box p { text-indent: 1em; }
aside.box p:first-of-type, .box-title + p { text-indent: 0; }
p.box-title { font-weight: bold; text-indent: 0; margin-bottom: 0.4em; }
.title-page { text-align: center; margin-top: 25%; }
.title-page h1 { text-align: center; page-break-before: avoid; }
"""


def xhtml(title, body):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">
<head><meta charset="utf-8"/><title>{html.escape(title)}</title><link rel="stylesheet" href="style.css"/></head>
<body>{body}</body></html>"""


def write_epub(book, cover_jpg, path):
    profile = book.profile
    book_id = f"urn:uuid:{uuid.uuid5(uuid.NAMESPACE_URL, profile.id)}"
    title = f"{profile.title}: {profile.subtitle}" if profile.subtitle else profile.title
    documents = [("title.xhtml", "Title", xhtml(title, (
        f'<div class="title-page"><h1>{html.escape(profile.title)}</h1>'
        f"<p>{html.escape(profile.subtitle)}</p><p><br/>{html.escape(profile.author)}</p>"
        f"<p><br/>{html.escape(profile.publisher)}, {html.escape(profile.year)}</p></div>")))]
    for n, section in enumerate(book.sections, 1):
        h1_class = ' class="part"' if section.kind == "part" else ""
        body = f"<h1{h1_class}>{html.escape(section.title)}</h1>" + "\n".join(
            filter(None, (render_block(b, book.vocabulary) for b in section.blocks)))
        documents.append((f"s{n:02d}.xhtml", section.title, xhtml(section.title, body)))

    nav_items = "".join(f'<li><a href="{f}">{html.escape(t)}</a></li>' for f, t, _ in documents)
    nav = xhtml("Contents", f'<nav epub:type="toc" id="toc"><h1>Contents</h1><ol>{nav_items}</ol></nav>')
    ncx_points = "".join(
        f'<navPoint id="n{i}" playOrder="{i}"><navLabel><text>{html.escape(t)}</text></navLabel>'
        f'<content src="{f}"/></navPoint>' for i, (f, t, _) in enumerate(documents, 1))
    ncx = f"""<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="{book_id}"/></head>
<docTitle><text>{html.escape(title)}</text></docTitle><navMap>{ncx_points}</navMap></ncx>"""

    manifest = [
        '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
        '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
        '<item id="css" href="style.css" media-type="text/css"/>',
        '<item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>',
    ]
    manifest += [f'<item id="d{i}" href="{f}" media-type="application/xhtml+xml"/>' for i, (f, _, _) in enumerate(documents)]
    manifest += [f'<item id="i{i}" href="{n}" media-type="image/jpeg"/>' for i, n in enumerate(book.images)]
    spine = "".join(f'<itemref idref="d{i}"/>' for i in range(len(documents)))
    modified = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">{book_id}</dc:identifier><dc:title>{html.escape(title)}</dc:title>
<dc:creator>{html.escape(profile.author)}</dc:creator><dc:publisher>{html.escape(profile.publisher)}</dc:publisher>
<dc:language>{profile.language}</dc:language><dc:date>{profile.year}</dc:date><meta property="dcterms:modified">{modified}</meta><meta name="cover" content="cover"/>
</metadata><manifest>{''.join(manifest)}</manifest><spine toc="ncx">{spine}</spine></package>"""

    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                   compress_type=zipfile.ZIP_DEFLATED)
        for name, content in [("content.opf", opf), ("nav.xhtml", nav), ("toc.ncx", ncx), ("style.css", CSS)]:
            z.writestr(f"OEBPS/{name}", content, compress_type=zipfile.ZIP_DEFLATED)
        for filename, _, content in documents:
            z.writestr(f"OEBPS/{filename}", content, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/cover.jpg", cover_jpg, compress_type=zipfile.ZIP_STORED)
        for name, data in book.images.items():
            z.writestr(f"OEBPS/{name}", data, compress_type=zipfile.ZIP_STORED)


def main(pdf_path, profile_path, epub_path):
    doc = pymupdf.open(pdf_path)
    profile = Profile.load(profile_path)
    book = Book(doc, profile)
    for n in profile.pages():
        book.add_page(n)
    book.finish()
    cover_page = doc[profile.cover_page - 1]
    cover = cover_page.get_pixmap(dpi=150, colorspace=pymupdf.csGRAY).tobytes("jpg", jpg_quality=85)
    write_epub(book, cover, epub_path)
    for s in book.sections:
        print(f"{s.kind:8} {len(s.blocks):5}  {s.title}")
    print("images:", len(book.images))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3])

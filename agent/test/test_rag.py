"""RAG 文本提取分派与语义族标注测试。"""

import pytest

from app.core.rag import RAGEngine, _EXTRACTORS, detect_kind

fixture = RAGEngine()


def test_detect_kind_classifies():
    assert detect_kind("notes.txt") == "text"
    assert detect_kind("report.pdf") == "pdf"
    assert detect_kind("doc.docx") == "docx"
    assert detect_kind("UPPER.PDF") == "pdf"


def test_detect_kind_reserved_families():
    assert detect_kind("a.xlsx") == "xlsx"
    assert detect_kind("a.pptx") == "pptx"
    assert detect_kind("photo.png") == "image"
    assert detect_kind("a.jpg") == "image"


def test_detect_kind_unknown():
    assert detect_kind("archive.zip") == "unknown"
    assert detect_kind("noext") == "unknown"


def test_extractors_registered_keys():
    for ext in (".txt", ".pdf", ".docx", ".xlsx", ".pptx", ".png", ".jpg"):
        assert ext in _EXTRACTORS


def test_extract_txt():
    assert fixture.extract_text("你好，world".encode(), "a.txt") == "你好，world"


@pytest.mark.parametrize(
    "content, ext, expect",
    [
        ('int main() { return 0; }'.encode(), "a.cc", "int main()"),
        ('print("hello")'.encode(), "a.py", "print"),
        ('function add(a, b) { return a + b; }'.encode(), "a.js", "function add"),
        ('package main\nfunc main() {}'.encode(), "a.go", "package main"),
        ('public class A {}'.encode(), "a.java", "public class A"),
    ],
)
def test_extract_code_languages_plain_text(content, ext, expect):
    """代码/配置文件走纯文本解码（可被语义索引）。"""
    assert fixture.extract_text(content, ext) != ""
    assert expect in fixture.extract_text(content, ext)


def test_extract_unknown_returns_empty():
    assert fixture.extract_text(b"\x00\x01", "a.zip") == ""


def test_extract_not_implemented_returns_empty():
    """MarkItDown 尚未覆盖的类型（gif/webp/bmp）返回空串，保持 skipped 行为。"""
    assert fixture.extract_text(b"GIF89a...", "a.gif") == ""


def test_extract_md_prefer_txt_entry():
    """快照：md 走纯文本解码（语义族 text，不再走 MarkItDown）。"""
    assert _EXTRACTORS[".md"][0] == "text"
    assert _EXTRACTORS[".md"][1].__name__ == "_extract_txt"


@pytest.mark.parametrize(
    "content, ext, expect",
    [
        ('<html><body><h1>标题</h1><p>正文内容</p></body></html>'.encode(), "a.html", "标题"),
        ("列A,列B\n值1,值2\n".encode(), "a.csv", "列A"),
    ],
)
def test_extract_markitdown_document(content, ext, expect):
    """MarkItDown 文本化：html/csv 等产出 Markdown 文本（含表格/标题）。"""
    text = fixture.extract_text(content, ext)
    assert text.strip() != ""
    assert expect in text


def test_extract_markitdown_missing_returns_empty():
    """损坏/不可识别的 xlsx 提取失败 → 空串（沿用 skipped 行为，不抛异常）。"""
    assert fixture.extract_text(b"\x00\x01\x02garbage", "a.xlsx") == ""


def test_chunk_text_keeps_sentence_boundaries_and_overlap():
    text = "第一段说明检索目标。第二段说明索引流程。第三段说明评估方法。第四段说明部署方式。"
    chunks = fixture.chunk_text(text, chunk_size=20, overlap=5)

    assert len(chunks) >= 2
    assert all(len(chunk) <= 20 for chunk in chunks)
    assert "第一段说明检索目标。" in chunks[0]
    assert any(set(chunks[i]) & set(chunks[i + 1]) for i in range(len(chunks) - 1))


def test_chunk_text_splits_long_sentence_without_empty_chunks():
    chunks = fixture.chunk_text("甲" * 55, chunk_size=20, overlap=5)

    assert chunks == ["甲" * 15, "甲" * 20, "甲" * 20, "甲" * 15]

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


def test_extract_unknown_returns_empty():
    assert fixture.extract_text(b"\x00\x01", "a.zip") == ""


def test_extract_reserved_none_returns_empty():
    """预留在案的格式（如 xlsx）在接入提取器前返回空串，保持 skipped 行为。"""
    assert fixture.extract_text(b"some binary", "a.xlsx") == ""


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


def test_extract_md_prefer_txt_entry():
    """快照：md 尚无提取器但已在表中（语义族 text）。"""
    assert _EXTRACTORS[".md"][0] == "text"
    assert _EXTRACTORS[".md"][1] is None

package multipart

import "testing"

func TestTotalChunksAndContiguousParts(t *testing.T) {
	total, err := TotalChunks(11, 5)
	if err != nil || total != 3 {
		t.Fatalf("total chunks = %d, err=%v", total, err)
	}
	if !Contiguous([]int{0, 1, 2}, 3) {
		t.Fatal("expected contiguous parts")
	}
	for _, parts := range [][]int{{1, 0, 2}, {0, 2}, {0, 1, 1}} {
		if Contiguous(parts, 3) {
			t.Fatalf("parts should be rejected: %v", parts)
		}
	}
}

func TestPartIndexBounds(t *testing.T) {
	if !ValidIndex(0, 1) || ValidIndex(-1, 1) || ValidIndex(1, 1) {
		t.Fatal("part index bounds are incorrect")
	}
}

package multipart

import "testing"

func TestNonFinalPartMustMeetS3Minimum(t *testing.T) {
	if ValidPartSize(0, 2, MinNonFinalPartSize-1) {
		t.Fatal("undersized non-final part must be rejected")
	}
	if !ValidPartSize(0, 2, MinNonFinalPartSize) || !ValidPartSize(1, 2, 1) {
		t.Fatal("valid multipart part sizes were rejected")
	}
}

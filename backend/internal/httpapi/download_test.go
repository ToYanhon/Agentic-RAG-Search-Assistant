package httpapi

import "testing"

func TestParseSingleRange(t *testing.T) {
	cases := []struct {
		value      string
		start, end int64
		ok         bool
	}{
		{"bytes=2-4", 2, 4, true}, {"bytes=8-", 8, 9, true}, {"bytes=-3", 7, 9, true}, {"bytes=10-12", 0, 0, false}, {"bytes=0-1,3-4", 0, 0, false},
	}
	for _, test := range cases {
		start, end, err := parseSingleRange(test.value, 10)
		if (err == nil) != test.ok || test.ok && (start != test.start || end != test.end) {
			t.Fatalf("%s => %d-%d err=%v", test.value, start, end, err)
		}
	}
}

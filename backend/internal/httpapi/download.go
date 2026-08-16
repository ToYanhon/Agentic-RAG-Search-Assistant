package httpapi

import (
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/clouddrive-ai/backend/internal/file"
)

type rangedDownload func(int64, int64) (file.Record, io.ReadCloser, error)

func writeDownload(w http.ResponseWriter, r *http.Request, record file.Record, body io.ReadCloser, rangeDownload rangedDownload) {
	defer body.Close()
	mime := record.MimeType
	if mime == "" {
		mime = "application/octet-stream"
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Content-Disposition", "attachment; filename="+strconv.Quote(record.Name))
	w.Header().Set("Accept-Ranges", "bytes")
	rangeHeader := r.Header.Get("Range")
	if rangeHeader == "" {
		w.Header().Set("Content-Length", strconv.FormatInt(record.Size, 10))
		_, _ = io.Copy(w, body)
		return
	}
	start, end, err := parseSingleRange(rangeHeader, record.Size)
	if err != nil {
		w.Header().Set("Content-Range", "bytes */"+strconv.FormatInt(record.Size, 10))
		w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		return
	}
	_ = body.Close()
	rangedRecord, rangedBody, err := rangeDownload(start, end-start+1)
	if err != nil {
		return
	}
	defer rangedBody.Close()
	length := end - start + 1
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	w.Header().Set("Content-Range", "bytes "+strconv.FormatInt(start, 10)+"-"+strconv.FormatInt(end, 10)+"/"+strconv.FormatInt(rangedRecord.Size, 10))
	w.WriteHeader(http.StatusPartialContent)
	_, _ = io.Copy(w, rangedBody)
}

func parseSingleRange(value string, size int64) (int64, int64, error) {
	if !strings.HasPrefix(value, "bytes=") || strings.Contains(value, ",") || size <= 0 {
		return 0, 0, errors.New("invalid range")
	}
	parts := strings.Split(strings.TrimPrefix(value, "bytes="), "-")
	if len(parts) != 2 {
		return 0, 0, errors.New("invalid range")
	}
	if parts[0] == "" {
		tail, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil || tail <= 0 {
			return 0, 0, errors.New("invalid range")
		}
		if tail > size {
			tail = size
		}
		return size - tail, size - 1, nil
	}
	start, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || start < 0 || start >= size {
		return 0, 0, errors.New("invalid range")
	}
	end := size - 1
	if parts[1] != "" {
		end, err = strconv.ParseInt(parts[1], 10, 64)
		if err != nil || end < start {
			return 0, 0, errors.New("invalid range")
		}
		if end >= size {
			end = size - 1
		}
	}
	return start, end, nil
}

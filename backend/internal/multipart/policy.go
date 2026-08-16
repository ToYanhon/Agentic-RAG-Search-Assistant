// Package multipart 包含分块上传的纯领域规则。
package multipart

import "errors"

var ErrInvalidParts = errors.New("parts are not contiguous")
var ErrPartTooSmall = errors.New("non-final part must be at least 5 MiB")

const MinNonFinalPartSize int64 = 5 * 1024 * 1024

func TotalChunks(size, chunkSize int64) (int, error) {
	if size <= 0 || chunkSize <= 0 {
		return 0, errors.New("size and chunk size must be positive")
	}
	total := (size-1)/chunkSize + 1
	if total > int64(^uint(0)>>1) {
		return 0, errors.New("too many parts")
	}
	return int(total), nil
}

func ValidIndex(index, total int) bool { return total > 0 && index >= 0 && index < total }

func Contiguous(parts []int, total int) bool {
	if len(parts) != total || total <= 0 {
		return false
	}
	for i, part := range parts {
		if part != i {
			return false
		}
	}
	return true
}

func ValidPartSize(index, total int, size int64) bool {
	return size > 0 && (index == total-1 || size >= MinNonFinalPartSize)
}

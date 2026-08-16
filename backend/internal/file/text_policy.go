package file

import (
	"strings"
)

var textExtensions = map[string]struct{}{
	"txt": {}, "md": {}, "markdown": {}, "csv": {}, "json": {}, "xml": {}, "yml": {}, "yaml": {}, "ini": {}, "log": {},
	"js": {}, "ts": {}, "tsx": {}, "jsx": {}, "html": {}, "css": {}, "py": {}, "go": {}, "java": {}, "c": {}, "h": {},
	"cpp": {}, "cc": {}, "cxx": {}, "hpp": {}, "sh": {}, "bat": {}, "sql": {},
}

func IsTextFile(name string) bool {
	dot := strings.LastIndex(name, ".")
	if dot < 0 || dot == len(name)-1 {
		return false
	}
	_, ok := textExtensions[strings.ToLower(name[dot+1:])]
	return ok
}

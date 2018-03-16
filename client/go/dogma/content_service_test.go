// Copyright 2018 LINE Corporation
//
// LINE Corporation licenses this file to you under the Apache License,
// version 2.0 (the "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at:
//
//   https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations
// under the License.

package dogma

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"testing"
)

func TestListFiles(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/tree/**", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		fmt.Fprint(w, `[{"path":"/a.json", "type":"JSON"},{"path":"/b.txt", "type":"TEXT"}]`)
	})

	entries, _, _ := c.ListFiles(context.Background(), "foo", "bar", "", "/**")
	want := []*Entry{{Path: "/a.json", Type: JSON}, {Path: "/b.txt", Type: Text}}
	if !reflect.DeepEqual(entries, want) {
		t.Errorf("ListFiles returned %+v, want %+v", entries, want)
	}
}

func TestListFiles_WithRevision(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/tree/**", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		testURLQuery(t, r, "revision", "2")
		fmt.Fprint(w, `[{"path":"/a.json", "type":"JSON"},{"path":"/b.txt", "type":"TEXT"}]`)
	})

	entries, _, _ := c.ListFiles(context.Background(), "foo", "bar", "2", "/**")
	want := []*Entry{{Path: "/a.json", Type: JSON}, {Path: "/b.txt", Type: Text}}
	if !reflect.DeepEqual(entries, want) {
		t.Errorf("ListFiles returned %+v, want %+v", entries, want)
	}
}

func TestGetFile(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/contents/b.txt",
		func(w http.ResponseWriter, r *http.Request) {
			testMethod(t, r, "GET")
			fmt.Fprint(w, `{"path":"/b.txt", "type":"TEXT", "content":"hello world~!"}`)
		})

	query := &Query{Path: "/b.txt", Type: Identity}
	entry, _, _ := c.GetFile(context.Background(), "foo", "bar", "", query)
	want := &Entry{Path: "/b.txt", Type: Text, Content: "hello world~!"}
	if !reflect.DeepEqual(entry, want) {
		t.Errorf("GetFile returned %+v, want %+v", entry, want)
	}
}

func TestGetFile_JSON(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/contents/a.json",
		func(w http.ResponseWriter, r *http.Request) {
			testMethod(t, r, "GET")
			fmt.Fprint(w, `{"path":"/a.json", "type":"JSON", "content":{"a":"b"}}`)
		})

	query := &Query{Path: "/a.json", Type: Identity}
	entry, _, _ := c.GetFile(context.Background(), "foo", "bar", "", query)
	want := &Entry{Path: "/a.json", Type: JSON, Content: map[string]interface{}{"a": "b"}}
	if !reflect.DeepEqual(entry, want) {
		t.Errorf("GetFile returned %+v, want %+v", entry, want)
	}
}

func TestGetFile_WithJSONPath(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/contents/a.json",
		func(w http.ResponseWriter, r *http.Request) {
			testMethod(t, r, "GET")
			testURLQuery(t, r, "jsonpath", "$.a")
			fmt.Fprint(w, `{"path":"/a.json", "type":"JSON", "content":"b"}`)
		})

	query := &Query{Path: "/a.json", Type: JSONPath, Expressions: []string{"$.a"}}
	entry, _, _ := c.GetFile(context.Background(), "foo", "bar", "", query)
	want := &Entry{Path: "/a.json", Type: JSON, Content: "b"}
	if !reflect.DeepEqual(entry, want) {
		t.Errorf("GetFile returned %+v, want %+v", entry, want)
	}
}

func TestGetFile_WithJSONPathAndRevision(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/contents/a.json",
		func(w http.ResponseWriter, r *http.Request) {
			testMethod(t, r, "GET")
			testURLQuery(t, r, "jsonpath", "$.a")
			testURLQuery(t, r, "revision", "-1")
			fmt.Fprint(w, `{"path":"/a.json", "type":"JSON", "content":"b"}`)
		})

	query := &Query{Path: "/a.json", Type: JSONPath, Expressions: []string{"$.a"}}
	entry, _, _ := c.GetFile(context.Background(), "foo", "bar", "-1", query)
	want := &Entry{Path: "/a.json", Type: JSON, Content: "b"}
	if !reflect.DeepEqual(entry, want) {
		t.Errorf("GetFile returned %+v, want %+v", entry, want)
	}
}

func TestGetFiles(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/contents/**", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		fmt.Fprint(w, `[{"path":"/a.json", "type":"JSON", "content":{"a":"b"}},
{"path":"/b.txt", "type":"TEXT", "content":"hello world~!"}]`)
	})

	entries, _, _ := c.GetFiles(context.Background(), "foo", "bar", "", "/**")
	want := []*Entry{{Path: "/a.json", Type: JSON, Content: map[string]interface{}{"a": "b"}},
		{Path: "/b.txt", Type: Text, Content: "hello world~!"}}
	if !reflect.DeepEqual(entries, want) {
		t.Errorf("GetFiles returned %+v, want %+v", entries, want)
	}
}

func TestGetHistory(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/commits/-2", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		testURLQuery(t, r, "to", "head")
		fmt.Fprint(w, `[{"revision":1, "author":"minux@m.x", "commitMessage":{"Summary":"Add a.json"}},
{"revision":2, "author":"minux@m.x", "commitMessage":{"Summary":"Edit a.json"}}]`)
	})

	history, _, _ := c.GetHistory(context.Background(), "foo", "bar", "-2", "head", "/**")
	want := []*Commit{{Revision: 1, Author: "minux@m.x", CommitMessage: &CommitMessage{Summary: "Add a.json"}},
		{Revision: 2, Author: "minux@m.x", CommitMessage: &CommitMessage{Summary: "Edit a.json"}}}
	if !reflect.DeepEqual(history, want) {
		t.Errorf("GetHistory returned %+v, want %+v", history, want)
	}
}

func TestGetDiff(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/compare",
		func(w http.ResponseWriter, r *http.Request) {
			testMethod(t, r, "GET")
			testURLQuery(t, r, "from", "3")
			testURLQuery(t, r, "to", "4")
			testURLQuery(t, r, "path", "/a.json")
			testURLQuery(t, r, "jsonpath", "$.a")
			fmt.Fprint(w, `{"path":"/a.json", "type":"APPLY_JSON_PATCH",
"content":[{
"op":"safeReplace",
"path":"",
"oldValue":"bar",
"value":"baz"
}]}`)
		})

	var content []interface{}

	content = append(content, map[string]interface{}{"op": "safeReplace",
		"path":     "",
		"oldValue": "bar",
		"value":    "baz"})

	query := &Query{Path: "/a.json", Type: JSONPath, Expressions: []string{"$.a"}}
	entry, _, _ := c.GetDiff(context.Background(), "foo", "bar", "3", "4", query)
	want := &Change{Path: "/a.json", Type: ApplyJSONPatch, Content: content}

	if !reflect.DeepEqual(entry, want) {
		t.Errorf("GetDiff returned %+v, want %+v", entry, want)
	}
}

func TestGetDiffs(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/compare", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		testURLQuery(t, r, "from", "1")
		testURLQuery(t, r, "to", "4")
		testURLQuery(t, r, "path", "/**")
		fmt.Fprint(w, `[{"path":"/a.json", "type":"APPLY_JSON_PATCH", "content":[{
"op":"safeReplace",
"path":"",
"oldValue":"bar",
"value":"baz"
}]},
{"path":"/b.txt", "type":"APPLY_TEXT_PATCH",
"content":"--- /b.txt\n+++ /b.txt\n@@ -1,1 +1,1 @@\n-foo\n+bar"}
]`)
	})

	changes, _, _ := c.GetDiffs(context.Background(), "foo", "bar", "1", "4", "/**")

	var content []interface{}
	content = append(content, map[string]interface{}{"op": "safeReplace",
		"path":     "",
		"oldValue": "bar",
		"value":    "baz"})
	want := []*Change{{Path: "/a.json", Type: ApplyJSONPatch, Content: content},
		{Path: "/b.txt", Type: ApplyTextPatch, Content: "--- /b.txt\n+++ /b.txt\n@@ -1,1 +1,1 @@\n-foo\n+bar"}}

	if !reflect.DeepEqual(changes, want) {
		t.Errorf("GetDiff returned %+v, want %+v", changes, want)
	}
}

func TestPush(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/contents", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "POST")
		testURLQuery(t, r, "revision", "-1")

		var reqBody Push
		json.NewDecoder(r.Body).Decode(&reqBody)
		changes := []*Change{{Path: "/a.json", Type: UpsertJSON, Content: map[string]interface{}{"a": "b"}}}
		want := Push{CommitMessage: &CommitMessage{Summary: "Add a.json"}, Changes: changes}
		if !reflect.DeepEqual(reqBody, want) {
			t.Errorf("Push request body %+v, want %+v", changes, want)
		}

		fmt.Fprint(w, `{"revision":2, "author":"minux@m.x", "entries":[{"path":"/a.json", "type":"JSON"}]}`)
	})

	commitMessage := CommitMessage{Summary: "Add a.json"}
	change := []*Change{{Path: "/a.json", Type: UpsertJSON, Content: map[string]interface{}{"a": "b"}}}
	commit, _, _ := c.Push(context.Background(), "foo", "bar", "-1", commitMessage, change)

	entries := []*Entry{{Path: "/a.json", Type: JSON}}
	want := &Commit{Revision: 2, Author: "minux@m.x", Entries: entries}
	if !reflect.DeepEqual(commit, want) {
		t.Errorf("Push returned %+v, want %+v", commit, want)
	}
}

func TestPush_TwoFiles(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo/repos/bar/contents", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "POST")
		testURLQuery(t, r, "revision", "-1")

		var reqBody Push
		json.NewDecoder(r.Body).Decode(&reqBody)
		changes := []*Change{{Path: "/a.json", Type: UpsertJSON, Content: map[string]interface{}{"a": "b"}},
			{Path: "/b.txt", Type: UpsertText, Content: "myContent"}}
		want := Push{CommitMessage: &CommitMessage{Summary: "Add a.json and b.txt"}, Changes: changes}
		if !reflect.DeepEqual(reqBody, want) {
			t.Errorf("Push request body %+v, want %+v", changes, want)
		}

		fmt.Fprint(w, `{"revision":3, "author":"minux@m.x", "entries":[{"path":"/a.json", "type":"JSON"},
{"path":"/b.txt", "type":"TEXT"}]}`)
	})

	commitMessage := CommitMessage{Summary: "Add a.json and b.txt"}
	changes := []*Change{{Path: "/a.json", Type: UpsertJSON, Content: map[string]interface{}{"a": "b"}},
		{Path: "/b.txt", Type: UpsertText, Content: "myContent"}}

	commit, _, _ := c.Push(context.Background(), "foo", "bar", "-1", commitMessage, changes)

	entries := []*Entry{{Path: "/a.json", Type: JSON}, {Path: "/b.txt", Type: Text}}
	want := &Commit{Revision: 3, Author: "minux@m.x", Entries: entries}
	if !reflect.DeepEqual(commit, want) {
		t.Errorf("Push returned %+v, want %+v", commit, want)
	}
}

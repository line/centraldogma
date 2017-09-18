// Copyright 2017 LINE Corporation
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

// Package json consists of the JSON transferable structs and JSON utilities.
package json

import (
	"bytes"
	"encoding/json"
	"io"
	"io/ioutil"
)

type Project struct {
	Name    string   `json:"name"`
	Schema  string   `json:"schema,omitempty"`
	Plugins []string `json:"plugins,omitempty"`
}

type Repository struct {
	Name string  `json:"name"`
	Head *Commit `json:"head,omitempty"`
}

type Commit struct {
	Rev       *Revision `json:"revision,omitempty"`
	Name      string    `json:"name,omitempty"`
	Timestamp string    `json:"timestamp,omitempty"`
	Summary   string    `json:"summary,omitempty"`
	Detail    *Comment  `json:"detail,omitempty"`
	Diffs     []Change  `json:"diffs,omitempty"`
}

type Revision struct {
	Major          int    `json:"major"`
	Minor          int    `json:"minor"`
	RevisionNumber string `json:"revisionNumber"`
}

type Comment struct {
	Content string `json:"content"`
	Markup  string `json:"markup"`
}

type Change struct {
	Path    string `json:"path"`
	Type    string `json:"type"`
	Content string `json:"content"`
}

type EntryWithCommit struct {
	File *Entry         `json:"file"`
	CM   *CommitMessage `json:"commitMessage"`
}

type Entry struct {
	Path    string `json:"path"`
	Type    string `json:"type"`
	Content string `json:"content"`
}

type CommitMessage struct {
	Summary string   `json:"summary"`
	Detail  *Comment `json:"detail"`
}

type EntryWithRevision struct {
	File *Entry `json:"file"`
	Rev  string `json:"revision"`
}

func Fill(data interface{}, resBody io.ReadCloser) error {
	body, _ := ioutil.ReadAll(resBody)
	err := json.Unmarshal([]byte(body), data)
	return err
}

func MarshalIndent(data interface{}) ([]byte, error) {
	return json.MarshalIndent(data, "", "    ")
}

func Marshal(data interface{}) ([]byte, error) {
	return json.Marshal(data)
}

func NewEncoder(w io.Writer) *json.Encoder {
	return json.NewEncoder(w)
}

func Valid(data []byte) bool {
	return json.Valid(data)
}

func Indent(src []byte) (*bytes.Buffer, error) {
	var prettyJSON bytes.Buffer
	if err := json.Indent(&prettyJSON, src, "", "    "); err != nil {
		return nil, err
	}
	return &prettyJSON, nil
}

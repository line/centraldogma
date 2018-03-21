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

type ChangeType int

const (
	UpsertJSON ChangeType = iota + 1
	UpsertText
	Remove
	Rename
	ApplyJSONPatch
	ApplyTextPatch
)

var changeTypeMap = map[string]ChangeType{
	"UPSERT_JSON":      UpsertJSON,
	"UPSERT_TEXT":      UpsertText,
	"REMOVE":           Remove,
	"RENAME":           Rename,
	"APPLY_JSON_PATCH": ApplyJSONPatch,
	"APPLY_TEXT_PATCH": ApplyTextPatch,
}

var changeTypeValues = [...]string{
	"UNKNOWN",
	"UPSERT_JSON",
	"UPSERT_TEXT",
	"REMOVE",
	"RENAME",
	"APPLY_JSON_PATCH",
	"APPLY_TEXT_PATCH",
}

type EntryType int

const (
	JSON EntryType = iota + 1
	Text
	Directory
)

var entryTypeMap = map[string]EntryType{
	"JSON":      JSON,
	"TEXT":      Text,
	"DIRECTORY": Directory,
}

var entryTypeValues = [...]string{
	"UNKNOWN",
	"JSON",
	"TEXT",
	"DIRECTORY",
}

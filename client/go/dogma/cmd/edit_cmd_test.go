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

package cmd

import (
	"reflect"
	"testing"
)

func TestNewEditCommand(t *testing.T) {
	defaultRemoteURL := "http://localhost:36462/"

	var tests = []struct {
		arguments []string
		revision  string
		want      interface{}
	}{
		{[]string{"foo/bar/a.txt"}, "",
			editFileCommand{repo: repositoryRequestInfo{
				remoteURL: defaultRemoteURL, projName: "foo", repoName: "bar",
				path: "/a.txt", revision: "-1"}}},

		{[]string{"foo/bar/b/a.txt"}, "10",
			editFileCommand{repo: repositoryRequestInfo{
				remoteURL: defaultRemoteURL, projName: "foo", repoName: "bar",
				path: "/b/a.txt", revision: "10"}}},
	}

	for _, test := range tests {
		c := newContext(test.arguments, defaultRemoteURL, test.revision)

		got, _ := newEditCommand(c)
		switch comType := got.(type) {
		case *editFileCommand:
			got2 := editFileCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newEditCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		default:
			t.Errorf("newEditCommand(%q) = %q, want: %q", test.arguments, got, test.want)
		}
	}
}

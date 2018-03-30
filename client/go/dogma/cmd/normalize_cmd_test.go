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

func TestNewNormalizeCommand(t *testing.T) {
	defaultRemoteURL := "http://localhost:36462/"

	var tests = []struct {
		arguments []string
		revision  string
		want      interface{}
	}{
		{[]string{"foo/bar"}, "",
			normalizeRevisionCommand{repo: repositoryRequestInfo{
				remoteURL: defaultRemoteURL,
				projName:  "foo",
				repoName:  "bar",
				path:      "/",
				revision:  "-1"}},
		},

		{[]string{"foo/bar/"}, "10",
			normalizeRevisionCommand{repo: repositoryRequestInfo{
				remoteURL: defaultRemoteURL,
				projName:  "foo",
				repoName:  "bar",
				path:      "/",
				revision:  "10"}},
		},
	}

	for _, test := range tests {
		c := newContext(test.arguments, defaultRemoteURL, test.revision)
		got, _ := newNormalizeCommand(c)
		switch comType := got.(type) {
		case *normalizeRevisionCommand:
			got2 := normalizeRevisionCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newNormalizeCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		default:
			t.Errorf("newNormalizeCommand(%q) = %q, want: %q", test.arguments, got, test.want)
		}
	}
}

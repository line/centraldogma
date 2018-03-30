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

func TestNewNewCommand(t *testing.T) {
	defaultRemoteURL := "http://localhost:36462/"

	var tests = []struct {
		arguments []string
		want      interface{}
	}{
		{[]string{"foo"}, newProjectCommand{remoteURL: defaultRemoteURL, name: "foo"}},
		{[]string{"/foo/"}, newProjectCommand{remoteURL: defaultRemoteURL, name: "foo"}},
		{[]string{"foo/bar"}, newRepositoryCommand{
			remoteURL: defaultRemoteURL,
			projName:  "foo",
			repoName:  "bar"},
		},
		{[]string{"/foo/bar/"}, newRepositoryCommand{
			remoteURL: defaultRemoteURL,
			projName:  "foo",
			repoName:  "bar"},
		},
	}

	for _, test := range tests {
		c := newContext(test.arguments, defaultRemoteURL, "")
		got, _ := newNewCommand(c)

		switch comType := got.(type) {
		case *newProjectCommand:
			got2 := newProjectCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newNewCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		case *newRepositoryCommand:
			got2 := newRepositoryCommand(*comType)
			want, _ := test.want.(newRepositoryCommand)
			if !reflect.DeepEqual(got2, want) {
				t.Errorf("newNewCommand(%q) = %q, want: %q", test.arguments, got2, want)
			}
		default:
			t.Errorf("newNewCommand(%q) = %q, want: %q", test.arguments, got, test.want)
		}
	}
}

func TestNewAddCommand(t *testing.T) {
	defaultRemoteURL := "http://localhost:36462/"

	var tests = []struct {
		arguments []string
		revision  string
		want      interface{}
	}{
		{[]string{"foo/bar", "a.txt"}, "",
			putFileCommand{
				repo: repositoryRequestInfo{
					remoteURL: defaultRemoteURL,
					projName:  "foo",
					repoName:  "bar",
					path:      "/a.txt",
					revision:  "-1"},
				localFilePath: "a.txt"},
		},

		{[]string{"foo/bar/b.txt", "a.txt"}, "",
			putFileCommand{
				repo: repositoryRequestInfo{
					remoteURL: defaultRemoteURL,
					projName:  "foo",
					repoName:  "bar",
					path:      "/b.txt",
					revision:  "-1"},
				localFilePath: "a.txt"},
		},

		{[]string{"foo/bar", "/Users/my/a.txt"}, "15",
			putFileCommand{
				repo: repositoryRequestInfo{
					remoteURL: defaultRemoteURL,
					projName:  "foo",
					repoName:  "bar",
					path:      "/a.txt",
					revision:  "15"},
				localFilePath: "/Users/my/a.txt"},
		},

		{[]string{"foo/bar", "Users/my/a.txt"}, "1",
			putFileCommand{
				repo: repositoryRequestInfo{
					remoteURL: defaultRemoteURL,
					projName:  "foo",
					repoName:  "bar",
					path:      "/a.txt",
					revision:  "1"},
				localFilePath: "Users/my/a.txt"},
		},

		{[]string{"/foo/bar/", "./b.txt"}, "-1",
			putFileCommand{
				repo: repositoryRequestInfo{
					remoteURL: defaultRemoteURL,
					projName:  "foo",
					repoName:  "bar",
					path:      "/b.txt",
					revision:  "-1"},
				localFilePath: "./b.txt"},
		},

		{[]string{"foo/bar/b.txt", "c.txt"}, "-100",
			putFileCommand{
				repo: repositoryRequestInfo{
					remoteURL: defaultRemoteURL,
					projName:  "foo",
					repoName:  "bar",
					path:      "/b.txt",
					revision:  "-100"},
				localFilePath: "c.txt"},
		},

		{[]string{"foo/bar/d", "e.txt"}, "1",
			putFileCommand{
				repo: repositoryRequestInfo{
					remoteURL: defaultRemoteURL,
					projName:  "foo",
					repoName:  "bar",
					path:      "/d",
					revision:  "1"},
				localFilePath: "e.txt"},
		},

		{[]string{"foo/bar/d/", "g.txt"}, "",
			putFileCommand{
				repo: repositoryRequestInfo{
					remoteURL: defaultRemoteURL,
					projName:  "foo",
					repoName:  "bar",
					path:      "/d/g.txt",
					revision:  "-1"},
				localFilePath: "g.txt"},
		},
	}

	for _, test := range tests {
		c := newContext(test.arguments, defaultRemoteURL, test.revision)

		got, _ := newPutCommand(c)
		switch comType := got.(type) {
		case *putFileCommand:
			got2 := putFileCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newPutCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		default:
			t.Errorf("newPutCommand(%q) = %q, want: %q", test.arguments, got, test.want)
		}
	}
}

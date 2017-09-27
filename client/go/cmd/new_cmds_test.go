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

package cmd

import (
	"flag"
	"net/url"
	"reflect"
	"testing"

	"github.com/line/centraldogma/client/go/json"
	"github.com/urfave/cli"
)

func TestNewNewCommand(t *testing.T) {
	defaultRemoteURI, _ := url.Parse("http://localhost:36462/api/v0/")

	var tests = []struct {
		arguments []string
		want      interface{}
	}{
		{[]string{"foo"}, newProjectCommand{remote: defaultRemoteURI, projectName: "foo"}},
		{[]string{"/foo/"}, newProjectCommand{remote: defaultRemoteURI, projectName: "foo"}},
		{[]string{"foo/bar"}, newRepositoryCommand{
			remote:      defaultRemoteURI,
			projectName: "foo",
			repository:  &json.Repository{Name: "bar"}},
		},
		{[]string{"/foo/bar/"}, newRepositoryCommand{
			remote:      defaultRemoteURI,
			projectName: "foo",
			repository:  &json.Repository{Name: "bar"}},
		},
	}

	for _, test := range tests {
		flags := flag.FlagSet{}
		flags.Parse(test.arguments)
		parent := cli.NewContext(nil, &flag.FlagSet{}, nil)
		c := cli.NewContext(nil, &flags, parent)
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
			if !equalNewRepositoryCommand(got2, want) {
				t.Errorf("newNewCommand(%q) = %q, want: %q", test.arguments, got2, want)
			}
		default:
			t.Errorf("newNewCommand(%q) = %q, want: %q", test.arguments, got, test.want)
		}
	}
}

func equalNewRepositoryCommand(repo1 newRepositoryCommand, repo2 newRepositoryCommand) bool {
	if repo1.projectName != repo2.projectName ||
		repo1.repository.Name != repo2.repository.Name {
		return false
	}
	return true
}

func TestNewAddCommand(t *testing.T) {
	defaultRemoteURI, _ := url.Parse("http://localhost:36462/api/v0/")

	var tests = []struct {
		arguments []string
		revision  string
		want      interface{}
	}{
		{[]string{"foo/bar", "a.txt"}, "",
			putFileCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/a.txt",
					revision:       "head"},
				localFilePath: "a.txt"},
		},

		{[]string{"foo/bar/b.txt", "a.txt"}, "",
			putFileCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/b.txt",
					revision:       "head"},
				localFilePath: "a.txt"},
		},

		{[]string{"foo/bar", "/Users/my/a.txt"}, "15",
			putFileCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/a.txt",
					revision:       "15"},
				localFilePath: "/Users/my/a.txt"},
		},

		{[]string{"foo/bar", "Users/my/a.txt"}, "1",
			putFileCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/a.txt",
					revision:       "1"},
				localFilePath: "Users/my/a.txt"},
		},

		{[]string{"/foo/bar/", "./b.txt"}, "-1",
			putFileCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/b.txt",
					revision:       "-1"},
				localFilePath: "./b.txt"},
		},

		{[]string{"foo/bar/b.txt", "c.txt"}, "-100",
			putFileCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/b.txt",
					revision:       "-100"},
				localFilePath: "c.txt"},
		},

		{[]string{"foo/bar/d", "e.txt"}, "1",
			putFileCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/d",
					revision:       "1"},
				localFilePath: "e.txt"},
		},

		{[]string{"foo/bar/d/", "g.txt"}, "",
			putFileCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/d/g.txt",
					revision:       "head"},
				localFilePath: "g.txt"},
		},
	}

	for _, test := range tests {
		flags := flag.FlagSet{}
		flags.Parse(test.arguments)
		parent := cli.NewContext(nil, &flag.FlagSet{}, nil)
		c := cli.NewContext(nil, &flags, parent)
		got, _ := newPutCommand(c, test.revision)
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

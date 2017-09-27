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

	"github.com/urfave/cli"
)

func TestNewLSCommand(t *testing.T) {
	defaultRemoteURI, _ := url.Parse("http://localhost:36462/api/v0/")

	var tests = []struct {
		arguments []string
		revision  string
		want      interface{}
	}{
		{[]string{""}, "", lsProjectCommand{remote: defaultRemoteURI}},
		{[]string{"/foo/"}, "", lsRepositoryCommand{remote: defaultRemoteURI, projectName: "foo"}},
		{[]string{"foo/"}, "", lsRepositoryCommand{remote: defaultRemoteURI, projectName: "foo"}},
		{[]string{"foo"}, "", lsRepositoryCommand{remote: defaultRemoteURI, projectName: "foo"}},
		{[]string{"foo/bar"}, "",
			lsPathCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/",
					revision:       "head",
				},
			},
		},
		{[]string{"foo/bar/"}, "",
			lsPathCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/",
					revision:       "head",
				},
			},
		},
		{[]string{"foo/bar/a"}, "",
			lsPathCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/a",
					revision:       "head",
				},
			},
		},
		{[]string{"foo/bar/a/"}, "100",
			lsPathCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/a/",
					revision:       "100",
				},
			},
		},
		{[]string{"foo/bar/a.txt"}, "",
			lsPathCommand{
				repo: repositoryRequestInfo{
					remote:         defaultRemoteURI,
					projectName:    "foo",
					repositoryName: "bar",
					repositoryPath: "/a.txt",
					revision:       "head",
				},
			},
		},
	}

	for _, test := range tests {
		flags := flag.FlagSet{}
		flags.Parse(test.arguments)
		parent := cli.NewContext(nil, &flag.FlagSet{}, nil)
		c := cli.NewContext(nil, &flags, parent)
		got, _ := newLSCommand(c, test.revision, 0)
		switch comType := got.(type) {
		case *lsProjectCommand:
			got2 := lsProjectCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newLSCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		case *lsRepositoryCommand:
			got2 := lsRepositoryCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newLSCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		case *lsPathCommand:
			got2 := lsPathCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newAddCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		default:
			t.Errorf("newLSCommand(%q) = %q, want: %q", test.arguments, got, test.want)
		}
	}
}

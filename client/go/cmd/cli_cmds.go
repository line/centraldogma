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
	"fmt"

	"github.com/line/centraldogma/client/go/json"
	"github.com/urfave/cli"
)

var revisionFlag = []cli.Flag{cli.StringFlag{
	Name:  "revision, r",
	Usage: "Specifies the revision to operate",
}}

var jsonPathFlag = cli.StringFlag{
	Name:  "jsonpath, j",
	Usage: "Specifies the JSON path expressions to apply",
}

var fromRevisionFlag = cli.StringFlag{
	Name:  "from",
	Usage: "Specifies the revision to apply from",
}

var toRevisionFlag = cli.StringFlag{
	Name:  "to",
	Usage: "Specifies the revision to apply until",
}

var printFormatFlags = []cli.Flag{
	cli.BoolFlag{
		Name:   "pretty",
		Hidden: true,
	},
	cli.BoolFlag{
		Name:   "simple",
		Hidden: true,
	},
	cli.BoolFlag{
		Name:   "json",
		Hidden: true,
	},
}

type PrintStyle int

const (
	_ PrintStyle = iota
	Pretty
	Simple
	JSON
)

func getPrintStyle(c *cli.Context) (PrintStyle, error) {
	var ps PrintStyle
	if c.Bool("pretty") {
		ps = Pretty
	}
	if c.Bool("simple") {
		if ps != 0 {
			return 0, fmt.Errorf("duplicate print style (pretty: %t, simple: %t, json: %t)\n",
				c.Bool("pretty"), c.Bool("simple"), c.Bool("json"))
		}
		ps = Simple
	}
	if c.Bool("json") {
		if ps != 0 {
			return 0, fmt.Errorf("duplicate print style (pretty: %t, simple: %t, json: %t)\n",
				c.Bool("pretty"), c.Bool("simple"), c.Bool("json"))
		}
		ps = JSON
	}
	if ps == 0 {
		ps = Pretty
	}
	return ps, nil
}

func printWithStyle(data interface{}, format PrintStyle) {
	// TODO implement this method
	buf, _ := json.MarshalIndent(data)
	fmt.Printf("%s\n", buf)
}

func newCommandLineError(c *cli.Context) *cli.ExitError {
	com := c.Command
	return cli.NewExitError("usage: "+com.Name+" "+com.ArgsUsage, 1)
}

func CLICommands() []cli.Command {
	return []cli.Command{
		{
			Name:      "ls",
			Usage:     "Lists the projects, repositories or files",
			ArgsUsage: "[<project_name>[/<repository_name>[/<path>]]]",
			Flags:     append(printFormatFlags, revisionFlag[0]),
			Action: func(c *cli.Context) error {
				style, err := getPrintStyle(c)
				if err != nil {
					return err
				}
				command, err := newLSCommand(c, c.String("revision"), style)
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "new",
			Usage:     "Creates a project or repository",
			ArgsUsage: "<project_name>[/<repository_name>]",
			Action: func(c *cli.Context) error {
				command, err := newNewCommand(c)
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "put",
			Usage:     "Puts a file to the repository",
			ArgsUsage: "<project_name>/<repository_name>[/<path>] file_path",
			Flags:     revisionFlag,
			Action: func(c *cli.Context) error {
				command, err := newPutCommand(c, c.String("revision"))
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "edit",
			Usage:     "Edits a file in the path",
			ArgsUsage: "<project_name>/<repository_name>/<path>",
			Flags:     revisionFlag,
			Action: func(c *cli.Context) error {
				command, err := newEditCommand(c, c.String("revision"))
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "get",
			Usage:     "Downloads a file in the path",
			ArgsUsage: "<project_name>/<repository_name>/<path>",
			Flags:     append(revisionFlag, jsonPathFlag),
			Action: func(c *cli.Context) error {
				command, err := newGetCommand(c, c.String("revision"), c.String("jsonpath"))
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "cat",
			Usage:     "Prints a file in the path",
			ArgsUsage: "<project_name>/<repository_name>/<path>",
			Flags:     append(revisionFlag, jsonPathFlag),
			Action: func(c *cli.Context) error {
				command, err := newCatCommand(c, c.String("revision"), c.String("jsonpath"))
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "rm",
			Usage:     "Removes a file in the path",
			ArgsUsage: "<project_name>/<repository_name>/<path>",
			Flags:     revisionFlag,
			Action: func(c *cli.Context) error {
				command, err := newRMCommand(c, c.String("revision"))
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "diff",
			Usage:     "Gets diff of given path",
			ArgsUsage: "<project_name>/<repository_name>[/<path>]",
			Flags:     append(printFormatFlags, fromRevisionFlag, toRevisionFlag),
			Action: func(c *cli.Context) error {
				style, err := getPrintStyle(c)
				if err != nil {
					return err
				}
				command, err := newDiffCommand(c, c.String("from"), c.String("to"), style)
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "log",
			Usage:     "Shows commit logs of the path",
			ArgsUsage: "<project_name>/<repository_name>[/<path>]",
			Flags:     append(printFormatFlags, fromRevisionFlag, toRevisionFlag),
			Action: func(c *cli.Context) error {
				style, err := getPrintStyle(c)
				if err != nil {
					return err
				}
				command, err := newLogCommand(c, c.String("from"), c.String("to"), style)
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "normalize",
			Usage:     "Normalizes a revision into an absolute revision",
			ArgsUsage: "<project_name>/<repository_name>",
			Flags:     revisionFlag,
			Action: func(c *cli.Context) error {
				command, err := newNormalizeCommand(c, c.String("revision"))
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
		{
			Name:      "search",
			Usage:     "Searches files matched by the term",
			ArgsUsage: "<project_name>/<repository_name> term",
			Flags:     append(printFormatFlags, revisionFlag[0]),
			Action: func(c *cli.Context) error {
				style, err := getPrintStyle(c)
				if err != nil {
					return err
				}
				command, err := newSearchCommand(c, c.String("revision"), style)
				if err != nil {
					return newCommandLineError(c)
				}
				err = command.execute(c)
				if err != nil {
					return cli.NewExitError(err, 1)
				}
				return nil
			},
		},
	}
}

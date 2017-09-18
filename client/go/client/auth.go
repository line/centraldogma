package client

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"os"
	"strings"
	"syscall"

	"github.com/line/centraldogma/client/go/service"
	"github.com/line/centraldogma/client/go/util/file"
	"golang.org/x/crypto/ssh/terminal"
)

func loginIfNeed(remoteURI *url.URL, token, username string) (string, error) {
	remote := (&url.URL{Scheme: remoteURI.Scheme, Host: remoteURI.Host}).String()
	PathOfSecurityCheck := remote + "/security_enabled"
	enabled, err := securityEnabled(PathOfSecurityCheck)
	if err != nil {
		return "", err
	}
	if !enabled {
		return "anonymous", nil
	}
	if len(token) != 0 {
		return token, nil
	}

	password := ""
	if len(username) == 0 {
		machine := file.NetrcInfo()
		if machine != nil {
			username = machine.Login
			password = machine.Password
			if len(username) == 0 || len(password) == 0 {
				return "", fmt.Errorf(
					"netrc file doesn't have enough information (username:%q)", username)
			}
		} else {
			username, err = getUsername()
			if err != nil {
				return "", err
			}
			password, err = getPassword()
			if err != nil {
				return "", err
			}
		}
	} else {
		password, err = getPassword()
		if err != nil {
			return "", err
		}
	}
	sessionID, err := login(remote, username, password)
	if err != nil {
		return "", err
	}

	return sessionID, nil
}
func login(remote string, username string, password string) (string, error) {
	values := url.Values{}
	values.Set("username", username)
	values.Set("password", password)
	values.Set("remember_me", "true")
	buf := new(bytes.Buffer)
	buf.WriteString(values.Encode())
	u, _ := url.Parse(remote + service.DefaultPathPrefix + "authenticate")
	req := &http.Request{Method: http.MethodPost, URL: u, Body: ioutil.NopCloser(buf)}
	req.Header = http.Header{"Content-Type": {"application/x-www-form-urlencoded"}}
	res, err := New().client.Do(req)
	if err != nil {
		return "", nil
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return "", fmt.Errorf("cannot login to %s (status: %s)", remote, res.Status)
	}
	b, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	sessionID := string(b)
	return sessionID, nil
}

func securityEnabled(remote string) (bool, error) {
	u, _ := url.Parse(remote)
	req := &http.Request{Method: http.MethodGet, URL: u}
	res, err := New().client.Do(req)
	if err != nil {
		return false, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return false, fmt.Errorf("authenticaion check is failed (status: %s)", res.Status)
	}
	b, _ := ioutil.ReadAll(res.Body)
	if string(b) == "true" {
		return true, nil
	}
	return false, nil
}

func getUsername() (string, error) {
	fmt.Print("Enter username: ")
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		return "", errors.New("you must input username")
	}
	line := strings.TrimSpace(scanner.Text())
	if len(line) == 0 {
		return "", errors.New("you must input username")
	}
	return line, nil
}

func getPassword() (string, error) {
	fmt.Print("Enter password: ")
	bytePassword, err := terminal.ReadPassword(int(syscall.Stdin))
	if err != nil {
		return "", err
	}
	fmt.Println()
	password := string(bytePassword)
	if len(password) == 0 {
		return "", errors.New("you must input password")
	}
	return password, nil
}

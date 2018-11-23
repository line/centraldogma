import { Button, InputGroup, Intent, Tooltip } from '@blueprintjs/core';
import * as qs from 'query-string';
import * as React from 'react';

interface ILoginFormState {
  account: string;
  disabled: boolean;
  password: string;
  showPassword: boolean;
}

interface ILoginFormProps {
  redirectTo: string;
}

export default class LoginForm extends React.PureComponent<
  ILoginFormProps,
  ILoginFormState
> {
  public state: ILoginFormState = {
    account: '',
    disabled: false,
    password: '',
    showPassword: false,
  };

  public render() {
    const sessionId = localStorage.getItem('sessionId');
    if (sessionId !== null) {
      this.toRoot();
      return;
    }

    const { account, disabled, password, showPassword } = this.state;
    const lockButton = (
      <Tooltip
        content={`${showPassword ? 'Hide' : 'Show'} Password`}
        disabled={disabled}
      >
        <Button
          icon={showPassword ? 'unlock' : 'lock'}
          intent={Intent.WARNING}
          minimal={true}
          onClick={this.onLockClick}
        />
      </Tooltip>
    );
    return (
      <>
        <InputGroup
          disabled={disabled}
          placeholder="Account"
          defaultValue={account}
          onChange={this.onAccountChange}
        />
        <InputGroup
          disabled={disabled}
          placeholder="Password"
          defaultValue={password}
          rightElement={lockButton}
          type={showPassword ? 'text' : 'password'}
          onChange={this.onPasswordChange}
          onKeyPress={this.onKeyPress}
        />
        <br />
        <Button
          disabled={disabled}
          text="Sign In"
          large={true}
          onClick={this.onSignInClick}
        />
      </>
    );
  }

  private toRoot = () => {
    window.location.href =
      this.props.redirectTo !== undefined ? this.props.redirectTo : '/';
  };

  private onAccountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    this.setState({
      account: e.target.value,
    });
  };

  private onPasswordChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    this.setState({
      password: e.target.value,
    });
  };

  private onLockClick = () => {
    this.setState({
      showPassword: !this.state.showPassword,
    });
  };

  private onKeyPress = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      this.onSignInClick();
    }
  };

  private onSignInClick = () => {
    if (this.state.account === '') {
      alert('Please enter your account.');
      return;
    }
    if (this.state.password === '') {
      alert('Please enter your password.');
      return;
    }

    this.setState({ disabled: true });
    fetch('/api/v1/login', {
      body: qs.stringify({
        password: this.state.password,
        username: this.state.account,
      }),
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      method: 'POST',
    }).then((response) => {
      const warn = () => {
        alert(
          'Cannot sign in Central Dogma web console. Please check your account and password again.',
        );
        this.setState({ disabled: false });
      };

      if (response.ok) {
        response
          .json()
          .then((token) => {
            localStorage.setItem('sessionId', token.access_token);
            this.toRoot();
          })
          .catch(() => warn());
      } else {
        warn();
      }
    });
  };
}

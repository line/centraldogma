import * as React from 'react';

interface ILogoutFormState {
  completed: boolean;
}

export default class LogoutForm extends React.PureComponent<
  {},
  ILogoutFormState
> {
  public state: ILogoutFormState = {
    completed: false,
  };

  public render() {
    const sessionId = localStorage.getItem('sessionId');
    if (sessionId !== null) {
      fetch('/api/v1/logout', {
        headers: {
          Authorization: `Bearer ${sessionId}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        method: 'POST',
      }).then(() => {
        localStorage.clear();
        this.setState({ completed: true });
      });
    } else {
      this.setState({ completed: true });
    }
    return <h2>Logout {this.state.completed ? 'completed' : 'in progress'}</h2>;
  }
}

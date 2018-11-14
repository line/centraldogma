import * as React from 'react';
import { Route } from 'react-router';

import './App.css';
import LoginForm from './LoginForm';
import LogoutForm from './LogoutForm';

import logo from './central_dogma.png';

class App extends React.Component {
  public render() {
    return (
      <div>
        <div className="App-header">
          <img src={logo} className="App-logo" />
        </div>
        <div className="App-body">
          <Route exact={true} path="/login" component={LoginForm} />
          <Route exact={true} path="/logout" component={LogoutForm} />
        </div>
      </div>
    );
  }
}

export default App;

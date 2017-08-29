'use strict';

angular.module('CentralDogmaAdmin')
    .factory('AuthServerProvider',
             function loginService($http, $window, Principal, ApiService) {
               return {
                 login: function (credentials) {
                   var data = 'j_username=' + encodeURIComponent(credentials.username) +
                              '&j_password=' + encodeURIComponent(credentials.password) +
                              '&_spring_security_remember_me=' + credentials.rememberMe + '&submit=Login';
                   return $http.post('api/authentication', data, {
                     headers: {
                       'Content-Type': 'application/x-www-form-urlencoded'
                     }
                   }).then(function (response) {
                     var token = response.headers('x-cd-token');
                     if (token !== null) {
                       $window.sessionStorage.setItem('token', token);
                     }
                     return response;
                   });
                 },

                 logout: function () {
                   ApiService.post('api/logout', '').then(function (data) {
                     $window.sessionStorage.clear();
                     Principal.refresh(); // Clear user info.
                     return data;
                   });
                 }
               };
             });

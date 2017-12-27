'use strict';

angular.module('CentralDogmaAdmin')
    .factory('AuthServerProvider',
             function loginService($http, $window, Principal, ApiService) {
               return {
                 login: function (credentials) {
                   var data = 'username=' + encodeURIComponent(credentials.username) +
                              '&password=' + encodeURIComponent(credentials.password) +
                              '&remember_me=' + credentials.rememberMe + '&submit=Login';
                   return ApiService.post('authenticate', data, {
                     headers: {
                       'Content-Type': 'application/x-www-form-urlencoded'
                     }
                   }).then(function (sessionId) {
                     if (sessionId !== null) {
                       $window.localStorage.setItem('sessionId', sessionId);
                     }
                     return sessionId;
                   });
                 },

                 logout: function () {
                   return ApiService.post('logout', '').then(function (data) {
                     $window.localStorage.clear();
                     Principal.refresh(); // Clear user info.
                     return data;
                   });
                 }
               };
             });

'use strict';

angular.module('CentralDogmaAdmin')
    .factory('Auth',
             function Auth($rootScope, $state, $q, Principal, AuthServerProvider, NotificationUtil, ApiService) {
               return {
                 login: function (credentials, callback) {
                   var cb = callback || angular.noop;
                   var deferred = $q.defer();

                   AuthServerProvider.login(credentials).then(function (data) {
                     // retrieve the logged account information
                     Principal.refresh().then(function () {
                       deferred.resolve(data);
                     });
                     return cb();
                   }).catch(function (err) {
                     this.logout();
                     deferred.reject(err);
                     return cb(err);
                   }.bind(this));

                   return deferred.promise;
                 },

                 logout: function () {
                   var deferred = $q.defer();
                   AuthServerProvider.logout().then(function () {
                     NotificationUtil.success("login.logged_out");
                     deferred.resolve(null);
                   });
                   return deferred.promise;
                 },

                 isEnabled: function () {
                   return ApiService.get('/security_enabled');
                 }
               };
             });

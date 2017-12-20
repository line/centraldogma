'use strict';

angular.module('CentralDogmaAdmin')
    .factory('Principal',
             function Principal($rootScope, $q, $window, User, CentralDogmaConstant) {
               var _identity, _authenticated = false;

               return {
                 isAuthenticated: function () {
                   return _authenticated;
                 },

                 projectRole: function (metadata) {
                   var me = null;
                   if (!_authenticated ||
                       angular.isUndefined(metadata) ||
                       angular.isUndefined(metadata.members) ||
                       metadata.members === null) {
                     return CentralDogmaConstant.PROJECT_ROLE_GUEST;
                   }
                   // We deal 'admin' as an owner of the project.
                   if (_identity.roles.indexOf(CentralDogmaConstant.LEVEL_ADMIN) !== -1) {
                    return CentralDogmaConstant.PROJECT_ROLE_OWNER;
                   }
                   Object.keys(metadata.members).forEach(function (value) {
                    if (angular.equals(value, _identity.email)) {
                      me = metadata.members[value];
                    }
                   });
                   if (me === null) {
                     return CentralDogmaConstant.PROJECT_ROLE_GUEST;
                   }
                   return me.role;
                 },

                 isInRole: function (role) {
                   if (!_authenticated || !_identity || !_identity.roles) {
                     return false;
                   }

                   return _identity.roles.indexOf(role) !== -1;
                 },

                 isUser: function () {
                   return this.isInRole(CentralDogmaConstant.LEVEL_USER);
                 },

                 isInAnyRole: function (roles) {
                   if (!_authenticated || !_identity.roles) {
                     return false;
                   }

                   for (var i = 0; i < roles.length; i++) {
                     if (this.isInRole(roles[i])) {
                       return true;
                     }
                   }

                   return false;
                 },

                 set: function (identity) {
                   if (typeof identity !== 'object') {
                     return;
                   }

                   $rootScope.user = _identity = identity;
                   if (identity !== null) {
                     _authenticated = true;
                     $rootScope.$broadcast('user:logged_in', identity);
                   }
                 },

                 clear: function () {
                   if (!_authenticated || !_identity) {
                     return false;
                   }

                   var oldIdentity = _identity;
                   _authenticated = false;
                   $rootScope.user = _identity = null;
                   $rootScope.$broadcast('user:logged_out', oldIdentity);
                   return true;
                 },

                 refresh: function () {
                   var deferred = $q.defer();

                   // retrieve the identity data from the server, update the identity object, and then resolve.
                   var $this = this;
                   User.get()
                       .then(function (account) {
                         $this.set(account);
                         deferred.resolve(_identity);
                       }, function () {
                         $this.clear();
                         deferred.resolve(null);
                         $window.localStorage.clear();
                       });

                   return deferred.promise;
                 }
               };
             });

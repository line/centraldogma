'use strict';

angular.module('CentralDogmaAdmin')
    .factory('User',
             function (ApiService) {
               return {
                 get: function () {
                   return ApiService.get('users/me');
                 }
               };
             });

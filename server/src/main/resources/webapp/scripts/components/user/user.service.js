'use strict';

angular.module('CentralDogmaAdmin')
    .factory('User',
             function (ApiService) {
               return {
                 get: function () {
                   return ApiService.get('api/users/me');
                 }
               };
             });

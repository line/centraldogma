'use strict';

angular.module('CentralDogmaAdmin')
    .factory('Hostname',
             function ($http, $q) {
               return {
                 get: function() {
                   var deferred = $q.defer();
                   $http.get('/hostname').then(function (response) {
                     deferred.resolve(response.data);
                   }, function (response) {
                     deferred.reject(response.status);
                   });
                   return deferred.promise;
                 }
               };
             });

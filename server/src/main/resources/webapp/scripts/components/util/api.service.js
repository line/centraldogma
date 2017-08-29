'use strict';

angular.module('CentralDogmaAdmin')
    .factory('ApiService', function ($http, $q, $window, StringUtil, NotificationUtil) {
               function makeRequest(verb, uri, data) {
                 var defer = $q.defer();

                 var config = {
                   method: verb,
                   url: uri
                 };

                 var token = $window.sessionStorage.getItem('token');
                 if (token !== null) {
                   config.headers = {
                     'x-cd-token': token
                   };
                 }

                 if (verb.match(/post|put/)) {
                   config.data = data;
                 }

                 $http(config).then(function (data) {
                     defer.resolve(data.data);
                   },
                   function (error) {
                     var rejected = {
                       status: error.status,
                       statusText: error.statusText,
                       message: error.data.message
                     };

                     var callback = defer.promise.$$state.pending;
                     if (callback && callback.length && typeof callback[0][2] !== 'function') {
                       NotificationUtil.error(rejected);
                     }

                     defer.reject(rejected);
                   });

                 return defer.promise;
               }

               return {
                 'get': function (uri) {
                   return makeRequest('get', uri);
                 },

                 post: function (uri, data) {
                   return makeRequest('post', uri, data);
                 },

                 put: function (uri, data) {
                   return makeRequest('put', uri, data);
                 },

                 'delete': function (uri) {
                   return makeRequest('delete', uri);
                 }
               };
             });

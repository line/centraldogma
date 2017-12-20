'use strict';

angular.module('CentralDogmaAdmin')
    .factory('StringUtil',
             function () {
               return {
                 isEmpty: function (value) {
                   if (value == null || value === 'undefined') {
                     return true;
                   }

                   if (!angular.isString(value)) {
                     throw new Error('given value is not a string: ' + value);
                   }

                   return value.length == 0;
                 },

                 isNotEmpty: function (value) {
                   return !this.isEmpty(value);
                 },

                 requireNotEmpty: function (value, message) {
                   if (this.isEmpty(value)) {
                     throw new Error(message);
                   }
                   return value;
                 },

                 defaultString: function (value, defaultValue) {
                   return this.isEmpty(value) ? defaultValue : value;
                 },

                 normalizePath: function (value) {
                   if (this.isEmpty(value)) {
                     return '/';
                   }

                   var temp = value.replace(/\/\/+/, '/');
                   if (temp === '/') {
                     return temp;
                   }

                   if (!this.startsWith(temp, '/')) {
                     temp = '/' + temp;
                   }
                   if (this.endsWith(temp, '/')) {
                     temp = temp.slice(0, -1);
                   }

                   return temp;
                 },

                 startsWith: function (str, prefix) {
                   return str.indexOf(prefix) === 0;
                 },

                 endsWith: function (str, suffix) {
                   return str.match(suffix + '$') == suffix;
                 },

                 encodePath: function (str) {
                   var split = str.split('/');
                   for (var index in split) {
                     split[index] = this.encodeParam(split[index]);
                   }
                   return split.join('/');
                 },

                 encodeParam: function (str) {
                   return encodeURIComponent(str);
                 },

                 encodeUri: function (uriComponents) {
                   var uri = [];
                   uriComponents.forEach(function (uriComponent) {
                     uriComponent.split('/').filter(function (value) {
                       // This would replace '//' with '/' in the result URI.
                       return value.length > 0;
                     }).forEach(function (value) {
                       uri.push(encodeURIComponent(value));
                     })
                   });
                   // Allow the first '/' and the last '/' of the string array.
                   var result = '';
                   if (this.startsWith(uriComponents[0], '/')) {
                     result += '/';
                   }
                   result += uri.join('/');
                   if (this.endsWith(uriComponents[uriComponents.length - 1], '/')) {
                     result += '/';
                   }
                   return result;
                 }
               };
             });

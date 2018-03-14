'use strict';

angular.module('CentralDogmaAdmin')
    .factory('NotificationUtil',
             function ($translate, StringUtil, Notification) {
               var lastLoginNotification = 0;
               return {
                 success: function () {
                   if (arguments.length <= 0 || arguments.length > 2) {
                     return;
                   }

                   var decoded = this.decodeArgs(arguments);
                   var translationId = decoded[0];
                   var interpolateParams = decoded[1];

                   $translate(translationId, interpolateParams).then(function (translated) {
                     Notification.success(translated);
                   }, function () {
                     Notification.success(translationId);
                   });
                 },
                 error: function () {
                   if (arguments.length <= 0 || arguments.length > 2) {
                     return;
                   }

                   var arg = arguments[0];
                   if (typeof arg === 'object' &&
                       typeof arg.status === 'number' && typeof arg.statusText === 'string') {
                     if (StringUtil.isNotEmpty(arg.message)) {
                       Notification.error(arg.message);
                     } else {
                       var message = arg.status + ' ' + arg.statusText;
                       if (arg.status === 401) {
                         // Avoid showing login popup multiple times.
                         var now = new Date().getTime();
                         var diff = now - lastLoginNotification;
                         if (diff > 3000) {
                           Notification.error({
                             message: message,
                             templateUrl: 'scripts/components/util/notification-template-with-login.html'
                           });
                           lastLoginNotification = now;
                         }
                       } else if (arg.status === 403) {
                         // TODO(hyangtack) Refine the error message.
                         Notification.error('Permission denied');
                       } else {
                         Notification.error(message);
                       }
                     }
                     return;
                   }

                   var decoded = this.decodeArgs(arguments);
                   var translationId = decoded[0];
                   var interpolateParams = decoded[1];

                   $translate(translationId, interpolateParams).then(function (translated) {
                     Notification.error(translated);
                   }, function () {
                     Notification.error(translationId);
                   });
                 },
                 decodeArgs: function (args) {
                   var translationId;
                   var interpolateParams;
                   if (args.length == 1) {
                     var arg = args[0];
                     if (typeof arg === 'object' &&
                         typeof arg.translationId === 'string' && typeof arg.interpolateParams === 'object') {
                       translationId = arg.translationId;
                       interpolateParams = arg.interpolateParams;
                     } else {
                       translationId = arg;
                       interpolateParams = {};
                     }
                   } else {
                     translationId = args[0];
                     interpolateParams = args[1];
                   }

                   return [ translationId, interpolateParams ];
                 }
               };
             });

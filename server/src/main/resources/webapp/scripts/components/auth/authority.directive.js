'use strict';

angular.module('CentralDogmaAdmin')
    .directive('hasAnyRole', ['Principal', function (Principal) {
      return {
        restrict: 'A',
        link: function (scope, element, attrs) {
          var setVisible = function () {
                element.removeClass('hidden');
              },
              setHidden = function () {
                element.addClass('hidden');
              },
              defineVisibility = function (reset) {
                var result;
                if (reset) {
                  setVisible();
                }

                result = Principal.isInAnyRole(roles);
                if (result) {
                  setVisible();
                } else {
                  setHidden();
                }
              },
              roles = attrs.hasAnyRole.replace(/\s+/g, '').split(',');

          if (roles.length > 0) {
            defineVisibility(true);
          }

          scope.$on('user:logged_in', function (event, data) {
            defineVisibility(true);
          });

          scope.$on('user:logged_out', function (event, data) {
            defineVisibility(true);
          });
        }
      };
    }])
    .directive('hasRole', ['Principal', function (Principal) {
      return {
        restrict: 'A',
        link: function (scope, element, attrs) {
          var setVisible = function () {
                element.removeClass('hidden');
              },
              setHidden = function () {
                element.addClass('hidden');
              },
              defineVisibility = function (reset) {
                var result;
                if (reset) {
                  setVisible();
                }

                result = Principal.isInRole(role);
                if (result) {
                  setVisible();
                } else {
                  setHidden();
                }
              },
              role = attrs.hasRole.replace(/\s+/g, '');

          if (role.length > 0) {
            defineVisibility(true);
          }

          scope.$on('user:logged_in', function (event, data) {
            defineVisibility(false);
          });

          scope.$on('user:logged_out', function (event, data) {
            defineVisibility(false);
          });
        }
      };
    }]);

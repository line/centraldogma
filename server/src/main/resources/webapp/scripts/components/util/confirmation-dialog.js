'use strict';

angular.module('CentralDogmaAdmin')
        .factory('ConfirmationDialog', function ($uibModal, $translate, $q) {
  return {
    open: function (customModalDefaults, customModalOptions) {
      const modalDefaults = {
        backdrop: true,
        keyboard: true,
        modalFade: true,
        templateUrl: 'scripts/components/util/confirmation-dialog.html'
      };
      const modalOptions = {
        text: 'Perform this action?'
      };

      //Create temp objects to work with since we're in a singleton service
      var tempModalDefaults = {};
      var tempModalOptions = {};

      //Map angular-ui modal custom defaults to modal defaults defined in service
      angular.extend(tempModalDefaults, modalDefaults, customModalDefaults);

      //Map modal.html $scope custom properties to defaults defined in service
      angular.extend(tempModalOptions, modalOptions, customModalOptions);

      if (!tempModalDefaults.controller) {
        tempModalDefaults.controller = function ($scope, $uibModalInstance) {
          $scope.modalOptions = tempModalOptions;
          $scope.modalOptions.ok = function () {
            $uibModalInstance.close(true);
          };
          $scope.modalOptions.close = function () {
            $uibModalInstance.close(false);
          };
        }
      }
      return $uibModal.open(tempModalDefaults).result;
    },

    openModal: function (translateId, interpolateParams) {
      const defer = $q.defer();
      const open = this.open;
      $translate(translateId, interpolateParams).then(function (translated) {
        return open({
          backdrop: 'static'
        }, {
          text: translated
        }).then(function (result) {
          if (result) {
            defer.resolve();
          }
        });
      }, function () {
        return open({
          backdrop: 'static'
        }, {
          text: translateId
        }).then(function (result) {
          if (result) {
            defer.resolve();
          }
        });
      });
      return defer.promise;
    }
  }
});

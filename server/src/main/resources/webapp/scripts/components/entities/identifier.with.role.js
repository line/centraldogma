'use strict';

angular.module('CentralDogmaAdmin')
        .factory('IdentifierWithRole', function () {
  function IdentifierWithRole() {
    this.elements = [];
  }

  IdentifierWithRole.prototype = {
    pushNew: function () {
      this.elements.push({
        id: null,
        role: null
      });
    },
    remove: function (elm) {
      this.elements = this.elements.filter(function (value) {
        return value !== elm;
      });
    }
  };

  return (IdentifierWithRole);
});

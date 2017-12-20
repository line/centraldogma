'use strict';

angular.module('CentralDogmaAdmin')
        .factory('Permission', function () {
  function Permission() {
    this.elements = [];
  }

  Permission.prototype = {
    pushNew: function () {
      this.elements.push({
        name: null,
        read: false,
        write: false
      });
    },
    remove: function (perm) {
      this.elements = this.elements.filter(function (value) {
        return value !== perm;
      });
    },
    validate: function (perm) {
      // If 'write' is permitted, 'read' should also be permitted.
      if (perm.write === true) {
        perm.read = true;
      }
    }
  };

  Permission.toSet = function (perm) {
    var set = [];
    if (perm.read === true) {
      set.push('READ');
    }
    if (perm.write === true) {
      set.push('WRITE');
    }
    return set;
  };

  Permission.toRequest = function (name, perm) {
    return {
      id: name,
      permissions: Permission.toSet(perm)
    };
  };

  Permission.makePermissionTable = function (permissionMap) {
    var permTable = {};
    Object.entries(permissionMap).forEach(function (entry) {
      const permSet = new Set(entry[1]);
      permTable[entry[0]] = {
        read: permSet.has('READ'),
        write: permSet.has('WRITE')
      };
    });
    return permTable;
  };

  return (Permission);
});

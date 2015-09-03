/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

angular.module(PKG.name + '.services')
  .service('myLoadingService', function($q, EventPipe) {
    var deferred;
    this.showLoadingIcon = function() {
      if (deferred) {
        return deferred.promise;
      } else {
        deferred = $q.defer();
        EventPipe.emit('showLoadingIcon');
        deferred.resolve(true);
        return deferred.promise;
      }
    };

    this.hideLoadingIcon = function() {
      if (!deferred) {
        return $q.when(true);
      } else {
        EventPipe.emit('hideLoadingIcon');
        deferred.resolve(true);
        deferred = null;
      }
    };

  });

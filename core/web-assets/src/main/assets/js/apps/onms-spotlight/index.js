const angular = require('vendor/angular-js');
require('../../lib/onms-http');
require('angular-bootstrap-toggle/dist/angular-bootstrap-toggle');
require('angular-bootstrap-toggle/dist/angular-bootstrap-toggle.css');
require('angular-ui-router');

const quickSearchTemplate  = require('./quicksearch.html');

(function() {
    'use strict';

    var MODULE_NAME = 'onms.spotlight';

    angular.module(MODULE_NAME, [
        'angular-loading-bar',
        'ngResource',
        'ui.router',
        'onms.http',
    ])
        .directive('onmsSpotlight', function() {
            return {
                restrict: 'E',
                transclude: false,
                templateUrl: quickSearchTemplate,
                controller: 'QuickSearchController'
            };
        })
        .factory('SearchResource', function($resource) {
            return $resource('api/v2/spotlight', {}, {
                'query':      { method: 'GET', isArray: true, cancellable: true },
            });
        })
        .controller('QuickSearchController', ['$scope', 'SearchResource', '$timeout', '$document', function($scope, SearchResource, $timeout, $document) {
            var KeyCodes = {
                ENTER: 13,
                SHIFT: 16,
                ESC: 27,
                KEY_LEFT: 37,
                KEY_UP: 38,
                KEY_RIGHT: 39,
                KEY_DOWN: 40,
            };

            $scope.query = '';
            $scope.results = {};
            $scope.performSearchExecuted = false;
            $scope.showLoadingIndicator = false;
            $scope.showLoadingIndicatorDelay = 250;
            $scope.performSearchDelay = 500; // in ms
            $scope.performSearchPromise = undefined;
            $scope.performSearchHandle = undefined;
            $scope.showLoadingIndicatorPromise = undefined;
            $scope.shiftLastPressed = undefined;
            $scope.selectedIndex = 0;

            $document.bind('mousedown', function(event) {
                var isChild = $('#onms-spotlight-form').has(event.target).length > 0;
                var isSelf = $('#onms-spotlight-form').is(event.target);
                var isInside = isChild || isSelf;
                if (!isInside) {
                    $scope.$apply(function() {
                        $scope.resetQuery();
                        $scope.cancelRequest();
                    });
                }
            });

            $document.bind('keyup', function(e) {
                // Search Focus Field
                $scope.$apply(function() {
                    if (e.keyCode === KeyCodes.SHIFT && new Date() - $scope.shiftLastPressed <= 350) {
                        angular.element('#query').focus();
                        angular.element('#query').select();
                        $scope.shiftLastPressed = undefined;
                    } else if(e.keyCode === KeyCodes.SHIFT) {
                        $scope.shiftLastPressed = new Date();
                    }

                    // Reset Search
                    if (e.keyCode === KeyCodes.ESC) {
                        $scope.resetQuery();
                        $scope.cancelRequest();
                    }
                });
            });

            $document.bind('keydown', function(e) {
                $scope.$apply(function() {
                    if ($scope.results.length > 0) {
                        if (e.keyCode === KeyCodes.KEY_UP || e.keyCode === KeyCodes.KEY_DOWN) {
                            $scope.navigateSearchResult(e.keyCode);
                        }
                        if (e.keyCode === KeyCodes.ENTER) {
                            $scope.resetQuery();
                            $scope.cancelRequest();
                            document.getElementById('onms-search-result-item-' + $scope.selectedIndex).click();
                        }
                    }
                });
            });

            $scope.navigateSearchResult = function(keyCode) {
                $scope.results[$scope.selectedIndex].selected = false;
                switch(keyCode) {
                    case KeyCodes.KEY_UP:
                        $scope.selectedIndex--;
                        break;
                    case KeyCodes.KEY_DOWN:
                        $scope.selectedIndex++;
                        break;
                    default:
                        break;
                }
                if ($scope.selectedIndex < 1) {
                    $scope.selectedIndex = 1;
                }
                if ($scope.selectedIndex >= $scope.results.length) {
                    $scope.selectedIndex = $scope.results.length - 1;
                }
                if ($scope.results[$scope.selectedIndex].group) {
                    $scope.navigateSearchResult(keyCode); // Skip group element
                } else {
                    $scope.results[$scope.selectedIndex].selected = true;
                }
            };

            $scope.resetQuery = function() {
                console.log('Reset input search query');
                $scope.query = '';
                $scope.results = [];
                $scope.performSearchExecuted = false;
                if ($scope.performSearchHandle) {
                    $scope.performSearchHandle.$cancelRequest();
                }
            };

            $scope.cancelRequest = function() {
                if ($scope.performSearchHandle) {
                    $scope.performSearchHandle.$cancelRequest();
                }
                $scope.showLoadingIndicator = false;
                $timeout.cancel($scope.showLoadingIndicatorPromise);
            };

            $scope.onQueryChange = function() {
                if ($scope.query.length == 0) {
                    $scope.resetQuery();
                    return;
                }
                if ($scope.query.length < 3) {
                    return;
                }

                // Stop any previous loading
                $timeout.cancel($scope.performSearchPromise);
                $scope.results = [];
                $scope.performSearchExecuted = false;

                // Start timeout before actually searching, this will allow for not invoking when the user
                // is still typing. Fiddle with $scope.loadingDelay to make it resolve faster
                $scope.performSearchPromise = $timeout(function() {
                    // Stop any previously started delay
                    $timeout.cancel($scope.showLoadingIndicatorPromise);

                    // Kick of loading indicator
                    $scope.showLoadingIndicatorPromise = $timeout(function() {
                        $scope.showLoadingIndicator = true;
                    }, $scope.showLoadingIndicatorDelay);

                    // Cancel any previous request
                    if ($scope.performSearchHandle) {
                        $scope.performSearchHandle.$cancelRequest();
                    }

                    // Kick of the search
                    $scope.performSearchHandle = SearchResource.query({'_s' : $scope.query},
                        function(data) {
                            console.log('Search result', data);
                            $scope.cancelRequest();
                            $scope.performSearchExecuted = true;

                            var results = [];
                            data.forEach(function(eachResult) {
                                // Create the header
                                results.push({
                                        context: eachResult.context.name,
                                        // Make the label have an s at the end if it has multiple items
                                        label: eachResult.results.length > 1 ? eachResult.context.name + 's' : eachResult.context.name,
                                        group: true,
                                        count: eachResult.results.length,
                                        totalCount: eachResult.totalCount
                                    }
                                );

                                eachResult.results.forEach(function(item) {
                                    item.group = false; // result cannot be a group
                                    results.push(item);

                                    // TODO MVR we first create this, and now we undo this, should be different
                                    var matches = item.matches;
                                    item.matches = [];
                                    matches.forEach(function(eachMatch) {
                                        eachMatch.values.forEach(function(eachValue) {
                                            item.matches.push({
                                                id: eachMatch.id,
                                                label: eachMatch.label,
                                                value: eachValue
                                            });
                                        });
                                    });
                                });
                            });
                            $scope.results = results;
                            if ($scope.results.length != 0) {
                                $scope.selectedIndex = 1;
                                $scope.results[$scope.selectedIndex].selected = true;
                            }
                        },
                        function(response) {
                            if (response.status >= 0) {
                                // TODO MVR error handling
                                console.log('ERROR', error);
                                $scope.cancelRequest();
                            } else {
                                console.log('CANCELLED');
                            }
                        }
                    );
                }, $scope.performSearchDelay);
            };

            function hashCode(s) {
                var h = 0;
                for(var i = 0; i < s.length; i++) {
                    h = Math.imul(31, h) + s.charCodeAt(i) | 0;
                }
                return h;
            }

            $scope.classes = ['primary', 'secondary', 'info', 'dark'];
            $scope.getClassForMatch = function(match) {
                var hash = hashCode(match.id);
                var index = Math.abs(hash) % $scope.classes.length;
                return 'badge-' + $scope.classes[index];
            }
        }])
    ;
}());
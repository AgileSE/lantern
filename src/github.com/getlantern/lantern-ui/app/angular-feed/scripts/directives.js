'use strict';

/*
Feeds directive shows localStorge cached feeds if available, and fetch server
in same time. It re-renders the feeds when remote feeds fetched, or calls
onError() if failed to fetch.
*/
angular.module('feeds-directives', []).directive('feed', ['feedService', '$compile', '$templateCache', '$http', function (feedService, $compile, $templateCache, $http) {
  return  {
    restrict: 'E',
    scope: {
      summary: '=',
      onFeedsLoaded: '&',
      onError: '&onError'
    },
    controller: ['$scope', '$element', '$attrs', '$q', '$sce', '$timeout', 'feedCache', 'gaMgr', function ($scope, $element, $attrs, $q, $sce, $timeout, feedCache, gaMgr) {
      $scope.$watch('finishedLoading', function (value) {
        if ($attrs.postRender && value) {
          $timeout(function () {
            new Function("element", $attrs.postRender + '(element);')($element);
          }, 0);
        }
      });

      var spinner = $templateCache.get('feed-spinner.html');
      $element.append($compile(spinner)($scope));

      function sanitizeFeedEntry(feedEntry) {
        feedEntry.title = $sce.trustAsHtml(feedEntry.title);
        feedEntry.contentSnippet = $sce.trustAsHtml(feedEntry.contentSnippet);
        feedEntry.content = $sce.trustAsHtml(feedEntry.content);
        feedEntry.publishedDate = new Date(feedEntry.publishedDate).getTime();
        return feedEntry;
      }

      function sanitizeEntries(entries) {
        for (var i = 0; i < entries.length; i++) {
          sanitizeFeedEntry(entries[i]);
        }
      }

      var templateRendered = false;
      function renderTemplate(templateHTML) {
        if (!templateRendered) {
          $element.append($compile(templateHTML)($scope));
        }
        templateRendered = true;
      }

      function render(feedsObj) {
        sanitizeEntries(feedsObj.entries);
        $scope.allEntries = feedsObj.entries;
        $scope.allFeeds = feedsObj.feeds;
        if ($attrs.templateUrl) {
          $http.get($attrs.templateUrl, {cache: $templateCache}).success(function (templateHtml) {
            renderTemplate(templateHtml);
          });
        }
        else {
          renderTemplate($templateCache.get('feed-list.html'));
        }
      }

      $attrs.$observe('url', function(url){
        var deferred = $q.defer();
        var feedsObj = feedCache.get(url);
        if (feedsObj) {
          console.log("show feeds in cache");
          render(feedsObj);
          deferred.resolve(feedsObj);
        } else {
          feedService.getFeeds(url, $attrs.fallbackUrl, gaMgr).then(function (feedsObj) {
            console.log("fresh copy of feeds loaded");
            feedCache.set(url, feedsObj);
            render(feedsObj);
            deferred.resolve(feedsObj);
          },function (error) {
            console.error("fail to fetch feeds: " +  error);
            if ($scope.onError) {
              $scope.onError(error);
            }
            $scope.error = error;
          });
        }

        deferred.promise.then(function(feedsObj) {
          if ($scope.onFeedsLoaded) {
            $scope.onFeedsLoaded();
          }
        }).finally(function () {
          $element.find('.spinner').slideUp();
          $scope.$evalAsync('finishedLoading = true')
        });

      });
    }]
  };
}]);

// Append the project badges at the end of the menu (sidenav).
function addBadge(parent, src, href) {
  var obj = document.createElement('object');
  obj.data = src;

  if (typeof href === 'string') {
    obj.style.pointerEvents = 'none';
    var a = document.createElement('a');
    a.href = href;
    a.target = '_blank';
    a.rel = 'nofollow noopener';
    a.appendChild(obj);
    parent.appendChild(a);
  } else {
    parent.appendChild(obj);
  }
  parent.appendChild(document.createElement('br'));
}


function addBadges(parent) {
  var li = document.createElement('li');
  li.className = 'toctree-l1';
  var div = document.createElement('div');
  div.className = 'project-badges';
  addBadge(div, 'https://img.shields.io/github/stars/line/centraldogma.svg?style=social');
  addBadge(div, 'https://img.shields.io/badge/chat-on%20slack-brightgreen.svg?style=social',
    'https://join.slack.com/t/central-dogma/shared_invite/enQtNjA5NDk5MTExODQzLWFhOWU2NGZhNDk3MjBmNzczZDYyZjRmMTI1MzdiNGI3OTcwNWZlOTkyY2U3Nzk4YTM2NzQ2NGJhMjQ1NzJlNzQ');
  addBadge(div, 'https://img.shields.io/travis/line/centraldogma/master.svg?style=flat-square',
    'https://travis-ci.org/line/centraldogma');
  addBadge(div, 'https://img.shields.io/appveyor/ci/line/centraldogma/master.svg?label=appveyor&style=flat-square',
    'https://ci.appveyor.com/project/line/centraldogma/branch/master');
  addBadge(div, 'https://img.shields.io/maven-central/v/com.linecorp.centraldogma/centraldogma-common.svg?style=flat-square',
    'https://search.maven.org/search?q=g:com.linecorp.centraldogma');
  addBadge(div, 'https://img.shields.io/github/commit-activity/m/line/centraldogma.svg?style=flat-square',
    'https://github.com/line/centraldogma/pulse');
  addBadge(div, 'https://img.shields.io/github/issues/line/centraldogma/good-first-issue.svg?label=good%20first%20issues&style=flat-square');
  addBadge(div, 'https://img.shields.io/codecov/c/github/line/centraldogma/master.svg?style=flat-square',
    'https://codecov.io/gh/line/centraldogma');
  li.appendChild(div);
  parent.appendChild(li);
}

var menus = document.getElementsByClassName("wy-menu wy-menu-vertical");
if (menus.length > 0) {
  var menu = menus[0];
  var lists = menu.getElementsByTagName('ul');
  if (lists.length > 0) {
    addBadges(lists[0]);
  }
}

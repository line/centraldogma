// Append the Slack invitation and project badges at the end of the menu (sidenav).
function addSlackInvitation(parent) {
  var li = document.createElement('li');
  li.className = 'toctree-l1';
  var div = document.createElement('div');
  div.className = 'slack-invitation';
  var script = document.createElement('script');
  script.type = 'text/javascript';
  script.src = 'https://line-slacknow.herokuapp.com/central-dogma/slackin.js';
  div.appendChild(script);
  li.appendChild(div);
  parent.appendChild(li);
}

function addBadge(parent, src, href) {
  var img = document.createElement('img');
  img.src = src;
  var a = document.createElement('a');
  a.href = href;
  a.appendChild(img);
  parent.appendChild(a);
  parent.appendChild(document.createElement('br'));
}

function addBadges(parent) {
  var li = document.createElement('li');
  li.className = 'toctree-l1';
  var div = document.createElement('div');
  div.className = 'project-badges';
  addBadge(div, 'https://img.shields.io/travis/line/centraldogma/master.svg?style=flat-square',
    'https://travis-ci.org/line/centraldogma');
  addBadge(div, 'https://img.shields.io/appveyor/ci/line/centraldogma/master.svg?label=appveyor&style=flat-square',
    'https://ci.appveyor.com/project/line/centraldogma/branch/master');
  addBadge(div, 'https://img.shields.io/maven-central/v/com.linecorp.centraldogma/centraldogma-common.svg?style=flat-square',
    'https://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22com.linecorp.centraldogma%22');
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
    addSlackInvitation(lists[0]);
    addBadges(lists[0]);
  }
}

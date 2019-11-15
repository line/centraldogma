.. _known-issues:

Known issues
============
- Security issues with Thrift RPC layer

  - Unlike REST requests, Thrift RPC requests do not go through Shiro authentication layer, which means they
    will be executed even if you are not authenticated by Shiro.

    - Still, a client must send the ``Authorization: bearer anonymous`` header at least, which should prevent
      most CSRF attacks.
    - If you want to reduce the attack surface even more, consider changing the hard-coded token
      ``"anonymous"``.

  - With Thrift RPC requests, the caller can specify arbitrary author of a commit, which can lead to authorship
    forgery.
  - Consider enforcing network-level access control over Thrift calls.
  - Note that the Thrift RPC layer is left only for backward compatibility, and will be removed in the future,
    in favor of the REST API.

Previously known issues
-----------------------
- DOM based, Cross-site Scripting (XSS) was found in Central Dogma.
  An attacker could exploit this by convincing an authenticated user to visit a specifically crafted URL on a CentralDogma server,
  allowing for the execution of arbitrary scripts on the client-side browser, resulting to perform unauthorized actions.

  - This issue affects: Central Dogma artifacts from 0.17.0 to 0.40.1.
  - The impact: Attacker is able to have victim execute arbitrary JavaScript code in the browser.
  - The component: Notification feature
  - The attack vector: Victim must open a specifically crafted URL.
  - The fixed version: 0.41.0 and later.
  - Please check `CVE-2019-6002 <https://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2019-6002>`_ to get more information.
  - If you found security bugs, please let us know  `dl_oss_dev@linecorp.com <mailto:dl_oss_dev@linecorp.com>`_ or
    send `Slack DM <https://join.slack.com/t/central-dogma/shared_invite/enQtNjA5NDk5MTExODQzLWFhOWU2NGZhNDk3MjBmNzczZDYyZjRmMTI1MzdiNGI3OTcwNWZlOTkyY2U3Nzk4YTM2NzQ2NGJhMjQ1NzJlNzQ>`_ to maintainer.

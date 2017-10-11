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

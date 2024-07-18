# Central Dogma web application

## Starting a test server

```sh
# In the project directory
./gradlew simpleTestServer
```

## Starting a test authentication server with Apache Shiro

Login with ID `foo` and password `bar`

```sh
# In the project root directory
./gradlew simpleTestShiroServer
```

## Building and start a client application

```sh
# Install dependencies
npm install

# Communicate with a test Central Dogma server
# An alias for `"NEXT_PUBLIC_HOST=\"http://127.0.0.1:36462\" next dev"`
npm run develop

# Use nextjs server and the API routes under pages/api
# An alias for `NEXT_PUBLIC_HOST='' next dev`
# Note: the nextjs server is not an actual Central Dogma server.
# It is only used as a mock to help with UI/UX development without having to run the Java server.
npm run mock
```

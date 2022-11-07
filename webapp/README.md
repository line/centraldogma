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

# Start Next.js dev server and automatically reload changed files
npm run dev
```

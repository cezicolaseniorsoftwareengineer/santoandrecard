# Build stage. `npm ci` installs exactly the lockfile, so the image cannot drift
# from what the test pipeline resolved.
FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
# The registry read repeatedly timed out from inside the builder on a slow link,
# failing the whole image for a transport problem rather than a dependency one.
RUN npm ci --fetch-timeout=600000 --fetch-retries=5
COPY tsconfig.json tsconfig.app.json angular.json ./
COPY src ./src
RUN npm run build

# Runtime stage. The unprivileged image listens on 8080 as UID 101 and needs no
# capability to bind the port, which keeps the pod admissible under the
# restricted Pod Security Standard with a read-only root filesystem.
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# @angular/build emits the bundle under browser/; only that subtree is served.
COPY --from=build /build/dist/banco-santo-andre-web/browser /usr/share/nginx/html
EXPOSE 8080

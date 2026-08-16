FROM golang:1.26 AS build
ARG VERSION=dev
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags "-X main.version=${VERSION}" -o /tunneld ./cmd/tunneld

FROM gcr.io/distroless/static:nonroot
COPY --from=build /tunneld /tunneld
ENTRYPOINT ["/tunneld", "serve"]

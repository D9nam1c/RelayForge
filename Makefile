.PHONY: env up down test verify seed demo demo-success demo-retry demo-rate-limit demo-dead-letter demo-replay demo-rotation load-test

env:
	./scripts/init-env.sh

up: env
	docker compose up --build --detach --wait --wait-timeout 420
	./scripts/seed.sh

down:
	docker compose down

test:
	MAVEN_USER_HOME="$$PWD/.cache/maven-home" ./mvnw test

verify:
	MAVEN_USER_HOME="$$PWD/.cache/maven-home" ./mvnw verify

seed:
	./scripts/seed.sh

demo:
	./scripts/demo.sh full

demo-success:
	./scripts/demo.sh success

demo-retry:
	./scripts/demo.sh retry

demo-rate-limit:
	./scripts/demo.sh rate-limit

demo-dead-letter:
	./scripts/demo.sh dead-letter

demo-replay:
	./scripts/demo.sh replay

demo-rotation:
	./scripts/demo.sh rotation

load-test:
	./scripts/load-test.sh

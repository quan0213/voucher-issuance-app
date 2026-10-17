.DEFAULT_GOAL := help
PYTHON ?= python3
USERS ?= 5000
CONCURRENCY ?= 1000

.PHONY: help setup db-up db-down up down restart run test smoke load verify logs status idea

help:
	@echo "make verify   - test, start PostgreSQL + API, and run the API smoke test"
	@echo "make up       - start PostgreSQL and the API in the background"
	@echo "make run      - start PostgreSQL and run the API in the foreground"
	@echo "make test     - isolated HTTP integration tests with real PostgreSQL 17"
	@echo "make smoke    - live API priority, idempotency, and sold-out checks"
	@echo "make load     - $(USERS) claims, at most $(CONCURRENCY) in flight"
	@echo "make down     - stop project-owned processes and preserve database data"
	@echo "make logs     - show recent local logs"
	@echo "make idea     - open this project in IntelliJ IDEA on macOS"

setup:
	$(PYTHON) scripts/manage.py setup

db-up:
	$(PYTHON) scripts/manage.py db-up

db-down:
	$(PYTHON) scripts/manage.py db-down

up:
	$(PYTHON) scripts/manage.py up

down:
	$(PYTHON) scripts/manage.py down

restart:
	$(MAKE) down
	$(MAKE) up

run:
	$(PYTHON) scripts/manage.py run

test:
	./mvnw -B verify

smoke:
	$(PYTHON) scripts/api_test.py --users 20 --capacity 5 --concurrency 20 --window-ms 5000 --check-priority

load:
	$(PYTHON) scripts/api_test.py --users $(USERS) --capacity 100 --concurrency $(CONCURRENCY) --window-ms 500

verify:
	$(MAKE) setup
	$(MAKE) test
	$(MAKE) up
	$(MAKE) smoke

logs:
	$(PYTHON) scripts/manage.py logs

status:
	$(PYTHON) scripts/manage.py status

idea:
	open -a "IntelliJ IDEA CE" "$(CURDIR)/pom.xml"

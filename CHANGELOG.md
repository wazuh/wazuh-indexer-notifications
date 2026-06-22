# CHANGELOG
All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). See the [CONTRIBUTING guide](./CONTRIBUTING.md#changelog) for instructions on how to add changelog entries.

## [v5.0.0]
### Added
- Initialize repository. [(#2)](https://github.com/wazuh/wazuh-indexer-notifications/issues/2)
- Implement wazuh-indexer-common-utils usage [(#17)](https://github.com/wazuh/wazuh-indexer-notifications/pull/17)
- Add active response channel [(#7)](https://github.com/wazuh/wazuh-indexer-notifications/pull/7)
- Add `--set-as-main` flag support to repository bumper [(#23)](https://github.com/wazuh/wazuh-indexer-notifications/pull/23)
- Implement batch Active Response indexing using BulkProcessor [(#67)](https://github.com/wazuh/wazuh-indexer-notifications/pull/67)
- Add revert bump functionality to repository bumper workflow [(#58)](https://github.com/wazuh/wazuh-indexer-notifications/pull/58)
- Create default notification channels on startup [(#68)](https://github.com/wazuh/wazuh-indexer-notifications/pull/68)
- Active Response events completeness [(#105)](https://github.com/wazuh/wazuh-indexer-notifications/pull/105)
- Add limit for the number of notification channels [(#118)](https://github.com/wazuh/wazuh-indexer-notifications/pull/118)

### Dependencies
-

### Changed
- Bump actions to NodeJS 24 [(#14)](https://github.com/wazuh/wazuh-indexer-notifications/pull/14)

### Deprecated
-

### Removed
-

### Fixed
- Fixed CodeQL compilation ([#15](https://github.com/wazuh/wazuh-indexer-notifications/pull/15))
- Fixed CodeQL common-utils dependency ([#35](https://github.com/wazuh/wazuh-indexer-notifications/pull/35))
- Fix SLF4J startup warning by replacing 1.x bridge with correct 2.x provider [(#110)](https://github.com/wazuh/wazuh-indexer-notifications/pull/110)

### Security
-

## Prior versions
- []()

[Unreleased 5.0.x]: https://github.com/wazuh/wazuh-indexer-notifications/compare/1929b1cf1d6b05d830b58916a8d1d6182f2f5ad8...main

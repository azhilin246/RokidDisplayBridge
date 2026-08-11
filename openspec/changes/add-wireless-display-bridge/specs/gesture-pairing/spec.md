## ADDED Requirements

### Requirement: Explicit one-time TOFU wireless pairing
An untrusted phone and glasses installation SHALL pair only after the user explicitly starts pairing on both devices.

#### Scenario: New devices begin pairing
- **WHEN** both applications are in explicit pairing mode and establish the candidate wireless socket
- **THEN** they SHALL create fresh ephemeral keys and nonces, exchange transcript-bound key-confirmation proofs, and SHALL NOT start DBP1 before mutual pairing confirmation

#### Scenario: Existing trust is present
- **WHEN** a different candidate requests pairing while one peer is stored
- **THEN** the application SHALL reject replacement unless the user explicitly forgets or replaces the stored peer

### Requirement: Double tap is reserved for application exit
Double tap SHALL retain its direct Display Bridge exit behavior in every application state.

#### Scenario: Double tap occurs during pairing
- **WHEN** the glasses recognize a double tap while the first pairing handshake is active
- **THEN** they SHALL abort pairing, close the candidate wireless connection, and execute the existing application-exit flow

#### Scenario: Double tap occurs outside pairing
- **WHEN** the glasses recognize a double tap during normal ready or playback behavior
- **THEN** the existing configured application-exit behavior SHALL remain unchanged

### Requirement: Pairing secrecy
Pairing SHALL use fresh transcript-bound cryptographic material and secret-free diagnostics.

#### Scenario: Pairing times out or disconnects
- **WHEN** the handshake deadline expires or the wireless socket closes before mutual confirmation
- **THEN** the applications SHALL erase ephemeral pairing state and SHALL NOT persist candidate trust

#### Scenario: Pairing is diagnosed
- **WHEN** pairing progress or failure is logged or displayed
- **THEN** the output SHALL omit private keys, shared secrets, traffic keys, nonces, and authentication tags

### Requirement: Authenticated reconnect and trust reset
After pairing, every wireless connection SHALL mutually authenticate fresh session nonces against the one stored peer, and either side SHALL provide an explicit local trust reset.

#### Scenario: Paired devices reconnect
- **WHEN** the stored peer identities exchange valid fresh transcript-bound proofs
- **THEN** the connection SHALL derive fresh traffic keys and proceed to encrypted DBP1 HELLO without repeating first-use pairing

#### Scenario: Unknown or invalid peer connects
- **WHEN** a candidate identifier is unknown or its proof is invalid
- **THEN** the connection SHALL close without starting DBP1 or disclosing stored peer details

#### Scenario: User forgets the peer
- **WHEN** the user activates Forget paired device
- **THEN** encrypted peer trust SHALL be deleted and the next wireless connection SHALL require explicit pairing

-- contestants table holds the contestants numbers (for voting) and names
CREATE TABLE contestants
(
  contestant_number integer     NOT NULL
, contestant_name   varchar(50) NOT NULL
, CONSTRAINT PK_contestants PRIMARY KEY
  (
    contestant_number
  )
);

-- Map of Area Codes and States for geolocation classification of incoming calls
CREATE TABLE area_code_state
(
  area_code smallint   NOT NULL
, state     varchar(2) NOT NULL
, CONSTRAINT PK_area_code_state PRIMARY KEY
  (
    area_code
  )
);

-- votes table holds every valid vote.
--   voters are not allowed to submit more than <x> votes, x is passed to client application
CREATE TABLE votes
(
  vote_id            bigint     NOT NULL,
  phone_number       bigint     NOT NULL
, state              varchar(2) NOT NULL -- REFERENCES area_code_state (state)
, contestant_number  integer    NOT NULL REFERENCES contestants (contestant_number)
, created            timestamp  NOT NULL
, CONSTRAINT PK_votes PRIMARY KEY
  (
    vote_id
  )
-- PARTITION BY ( phone_number )
);

CREATE TABLE votes_by_contestant_number_state
(
  contestant_number  int        NOT NULL
, state              varchar(2) NOT NULL
, num_votes          int
, CONSTRAINT PK_votes_by_contestant_number_state PRIMARY KEY
  (
    contestant_number,
    state
  )
);

-- rollup of votes by phone number, used to reject excessive voting
CREATE TABLE votes_by_phone_number
(
    phone_number     bigint    NOT NULL,
    num_votes        int,
    CONSTRAINT PK_votes_by_phone_number PRIMARY KEY
    (
      phone_number
    )
);

CREATE TABLE current_leader
(
  contestant_number  integer    NOT NULL
, created            timestamp  NOT NULL
, numVotes	     integer    NOT NULL
);

-- streams for processing ---
CREATE STREAM votes_stream
(
  vote_id            bigint     NOT NULL,
  phone_number       bigint     NOT NULL
, state              varchar(2) NOT NULL
, contestant_number  integer    NOT NULL
, created            timestamp  NOT NULL
);

-- result from step 1: Validate contestants
CREATE STREAM S1
(
  vote_id            bigint     NOT NULL,
  phone_number       bigint     NOT NULL
, state              varchar(2) NOT NULL
, contestant_number  integer    NOT NULL
, created            timestamp  NOT NULL
);

-- result from step2: Validate number of votes
CREATE STREAM S2
(
  vote_id            bigint     NOT NULL,
  phone_number       bigint     NOT NULL
, state              varchar(2) NOT NULL
, contestant_number  integer    NOT NULL
, created            timestamp  NOT NULL
);

-- used to update the table votes_by_phone_number
CREATE STREAM S3
(
    phone_number     bigint    NOT NULL,
    num_votes        int
);

-- used to update the table votes_by_contestant_number_state
CREATE STREAM S4
(
  contestant_number  int        NOT NULL
, state              varchar(2) NOT NULL
, num_votes          int
);

-- result from step2: Send votes to the window
CREATE STREAM S5
(
  vote_id            bigint     NOT NULL
, contestant_number  integer    NOT NULL
, created            timestamp  NOT NULL
);

CREATE WINDOW W_ROWS ON S5 ROWS 100 SLIDE 10;

CREATE STREAM S6
(
  contestant_number  integer    NOT NULL
, created            timestamp  NOT NULL
, numVotes	     integer    NOT NULL
);


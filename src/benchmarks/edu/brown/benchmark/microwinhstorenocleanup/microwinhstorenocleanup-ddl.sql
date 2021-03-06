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

CREATE TABLE w_rows
(
  vote_id            bigint     NOT NULL,
  phone_number       bigint     NOT NULL
, state              varchar(2) NOT NULL -- REFERENCES area_code_state (state)
, contestant_number  integer    NOT NULL REFERENCES contestants (contestant_number)
, created            timestamp  NOT NULL
, CONSTRAINT PK_win PRIMARY KEY
  (
    vote_id
  )
-- PARTITION BY ( phone_number )
);

CREATE INDEX idx_rows ON w_rows(vote_id);

CREATE TABLE leaderboard
(
  contestant_number  integer   NOT NULL
, numvotes           integer   NOT NULL
, CONSTRAINT PK_leaderboard PRIMARY KEY
  (
    contestant_number
  )

);

CREATE TABLE staging_count
(
   row_id             integer   NOT NULL,
   cnt		     integer   NOT NULL

, CONSTRAINT PK_stagingcount PRIMARY KEY
  (
    row_id
  )
);

CREATE TABLE window_count
(
   row_id             integer   NOT NULL,
   cnt		     integer   NOT NULL

, CONSTRAINT PK_windowcount PRIMARY KEY
  (
    row_id
  )
);

CREATE TABLE max_win_id
(
   row_id             integer   NOT NULL,
   vote_id	     bigint   NOT NULL

, CONSTRAINT PK_maxwinid PRIMARY KEY
  (
    row_id
  )
);


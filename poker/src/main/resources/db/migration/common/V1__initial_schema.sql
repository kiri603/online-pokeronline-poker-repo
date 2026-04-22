CREATE TABLE user_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(20) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_login_at TIMESTAMP NULL,
    session_version VARCHAR(64) NULL
);

CREATE UNIQUE INDEX uk_user_account_username ON user_account (username);

CREATE TABLE user_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    total_games INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    experience INT NOT NULL DEFAULT 0,
    last_daily_sign_in_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_stats_user_id UNIQUE (user_id),
    CONSTRAINT fk_user_stats_user_account FOREIGN KEY (user_id) REFERENCES user_account (id)
);

CREATE TABLE user_login_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    username VARCHAR(20) NULL,
    ip VARCHAR(64) NULL,
    user_agent VARCHAR(255) NULL,
    success BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_login_log_user_account FOREIGN KEY (user_id) REFERENCES user_account (id)
);

CREATE INDEX idx_user_login_log_user_id ON user_login_log (user_id);
CREATE INDEX idx_user_login_log_username ON user_login_log (username);
CREATE INDEX idx_user_login_log_created_at ON user_login_log (created_at);

CREATE TABLE remember_login_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NULL,
    CONSTRAINT uk_remember_login_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_remember_login_token_user_account FOREIGN KEY (user_id) REFERENCES user_account (id)
);

CREATE INDEX idx_remember_login_token_user_id ON remember_login_token (user_id);
CREATE INDEX idx_remember_login_token_expires_at ON remember_login_token (expires_at);

CREATE TABLE friend_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_a_id VARCHAR(32) NOT NULL,
    user_b_id VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_friend_pair UNIQUE (user_a_id, user_b_id)
);

CREATE INDEX idx_friend_relation_user_a ON friend_relation (user_a_id);
CREATE INDEX idx_friend_relation_user_b ON friend_relation (user_b_id);

CREATE TABLE friend_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(32) NOT NULL,
    to_user_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    responded_at TIMESTAMP NULL
);

CREATE INDEX idx_friend_request_from_user_id ON friend_request (from_user_id);
CREATE INDEX idx_friend_request_to_user_id ON friend_request (to_user_id);
CREATE INDEX idx_friend_request_status ON friend_request (status);

CREATE TABLE direct_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(32) NOT NULL,
    to_user_id VARCHAR(32) NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP NULL
);

CREATE INDEX idx_direct_message_sender_receiver ON direct_message (from_user_id, to_user_id, created_at);
CREATE INDEX idx_direct_message_receiver_read ON direct_message (to_user_id, read_at);

CREATE TABLE room_invite (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(32) NOT NULL,
    to_user_id VARCHAR(32) NOT NULL,
    room_id VARCHAR(32) NOT NULL,
    room_password VARCHAR(16) NULL,
    private_room BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    responded_at TIMESTAMP NULL
);

CREATE INDEX idx_room_invite_from_user_id ON room_invite (from_user_id);
CREATE INDEX idx_room_invite_to_user_id ON room_invite (to_user_id);
CREATE INDEX idx_room_invite_status ON room_invite (status);

CREATE TABLE game_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    player_count INT NOT NULL,
    winner_user_id VARCHAR(32) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_game_record_room_id ON game_record (room_id);
CREATE INDEX idx_game_record_ended_at ON game_record (ended_at);

CREATE TABLE game_record_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_record_id BIGINT NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    result VARCHAR(12) NOT NULL,
    CONSTRAINT fk_game_record_participant_record FOREIGN KEY (game_record_id) REFERENCES game_record (id)
);

CREATE INDEX idx_game_record_participant_user_id ON game_record_participant (user_id);
CREATE INDEX idx_game_record_participant_record_id ON game_record_participant (game_record_id);

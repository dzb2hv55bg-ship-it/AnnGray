-- Account-scoped stable local room UUID -> remote room UUID mapping.
CREATE TABLE IF NOT EXISTS room_creation_keys (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 client_room_id UUID NOT NULL,
 space_id UUID NOT NULL UNIQUE REFERENCES spaces(id) ON DELETE CASCADE,
 PRIMARY KEY(user_id,client_room_id)
);

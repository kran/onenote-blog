CREATE TABLE IF NOT EXISTS t_page
    (
        id varchar(100) NOT NULL
            PRIMARY KEY,
        section_id varchar(100) DEFAULT '' NOT NULL,
        title varchar(1000) DEFAULT '' NOT NULL,
        cover varchar(200) DEFAULT '' NOT NULL,
        summary varchar(1000) DEFAULT '' NOT NULL,
        content longtext NULL,
        created_date_time datetime NOT NULL,
        last_modified_date_time datetime NOT NULL
    );

CREATE TABLE IF NOT EXISTS t_section
    (
        id varchar(100) NOT NULL PRIMARY KEY,
        display_name varchar(100) DEFAULT '' NOT NULL,
        created_date_time datetime NOT NULL,
        last_modified_date_time datetime NOT NULL,
        is_default int DEFAULT 0 NOT NULL
    );


CREATE TABLE IF NOT EXISTS t_kv
    (
        id bigint AUTO_INCREMENT
            PRIMARY KEY,
        name varchar(40) DEFAULT '' NOT NULL,
        val text NOT NULL,
        update_ts timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT uniq_name UNIQUE (name)
    );
# Spring SQL 인젝션 실습

가상 상품 두 건을 조회하는 독립 Spring Boot 앱입니다. DevLink 애플리케이션과 별도의 Gradle 프로젝트이며, 로컬 주소와 인메모리 H2를 사용합니다.

저장소 루트에서 Java 21로 실행합니다.

```sh
./gradlew -p security-lab test
./gradlew -p security-lab bootRun
```

Windows에서는 `./gradlew.bat`를 사용합니다. 서버를 띄우면 아래 두 요청으로 검색 결과를 비교할 수 있습니다. PowerShell에서는 `curl.exe`를 사용합니다.

```sh
curl -G http://127.0.0.1:8080/products --data-urlencode "name=Notebook"
curl -G http://127.0.0.1:8080/products --data-urlencode "name=' OR '1'='1"
```

취약한 버전에서는 Spring의 `@RequestParam("name")`이 받은 값을 SQL 문자열에 이어 붙여 `JdbcTemplate.queryForList`에 전달합니다. 첫 요청은 `["Notebook"]`, 두 번째 요청은 조건이 항상 참이 되어 `["Notebook","Pencil"]`을 반환합니다.

수정한 버전에서는 SQL의 `WHERE name = ?`를 고정하고 요청값을 JDBC 인자로 전달합니다. 첫 요청은 여전히 `["Notebook"]`이고, 두 번째 요청은 입력 전체를 상품 이름으로 검색하므로 `[]`을 반환합니다. 두 요청을 Spring MVC와 H2를 사용한 테스트로 검증합니다.

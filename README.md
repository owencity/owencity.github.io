# 김동규의 GitHub와 개발 기록

자기소개, GitHub 프로젝트와 활동, 블로그를 함께 보여주는 사이트다. [사이트 보기](https://owencity.github.io) · [포트폴리오](https://www.devkdk.com)

## 내 컴퓨터에서 보기

Node.js 24가 필요하다. 이 폴더에서 다음 명령을 실행한다.

```sh
npm ci
npm run build:preview
npm run preview
```

브라우저에서 `http://127.0.0.1:4184`를 연다. 서버 없이 `dist/index.html`을 열어도 볼 수 있다. Markdown이나 화면을 수정했다면 `npm run build:preview`로 다시 만든 뒤 새로고침한다.

## 글 작성

1. `content/posts/`에 Markdown 파일을 만든다. 그림은 `public/assets/`에 넣는다.
2. `content/posts.json`에 제목, 요약, 날짜, 파일 경로를 추가한다. 작성 중인 글은 `draft: true`로 둔다.
3. 미리보기에서 본문, 목차, 그림을 확인한다.
4. 검수를 마친 글만 `draft: false`로 바꾼다. `npm run build`와 사이트 배포에는 이 글만 포함된다. 공개 저장소에 소스를 올리면 초안 Markdown도 저장소에서는 읽을 수 있으므로, 원문까지 비공개로 둘 글은 업로드하지 않는다.

표와 Mermaid 다이어그램을 지원한다. HTML 파일은 빌드할 때 생성하므로 직접 수정하지 않는다. 현재 재고 동시성 글은 커머스 저장소에서 가져온 미검수 사본이다. 커머스 원본의 변경을 자동으로 가져오는 연결은 아직 없으며, 공개하기 전에 최신 원본과 대조한다.

## 픽셀 배경

서로 다른 풍경 10개를 Canvas로 그린다. 재생 중에는 10초마다 풍경이 바뀐다. 풍경 그림 바로 아래 조작부에서 정지하거나 원하는 풍경을 고를 수 있다. 정지하면 풍경 전환과 배·기차 등의 움직임이 모두 멈춘다. 기기의 동작 줄이기 설정이 켜져 있으면 정지 상태로 시작하고, 다른 탭을 보는 동안에는 재생 시간을 세지 않는다.

풍경은 `public/pixel-scenes.js`, 재생 동작은 `public/scene-player.js`, 테두리·색상은 `public/pixel-theme.css`에서 수정한다. 풍경과 별개로 글과 메뉴는 불투명한 패널에 표시한다.

## GitHub 정보

`npm run sync`로 공개 프로필, 저장소, 최근 공개 활동을 가져온다. 결과는 `data/github.json`에 필요한 항목만 저장한다. 방문자의 브라우저에서 인증 토큰을 사용하지 않는다.

- 프로젝트: 본인 소유의 공개 저장소 중 GitHub에 고정한 저장소를 우선한다. 고정 목록을 조회하지 못한 경우 `site.config.json`에 지정한 네 저장소를 보여준다. 전체 저장소는 별도로 펼쳐 검색할 수 있다. 다른 계정이나 조직 소유의 고정 저장소는 현재 조회 범위에 포함하지 않는다.
- 기여 기록: 인증된 GraphQL 조회 결과가 있을 때 날짜별 잔디를 표시한다. GitHub 설정에 따라 비공개 활동의 익명 집계가 포함될 수 있다. 조회 결과가 없으면 안내와 GitHub 링크를 보여준다.
- 최근 활동: 공개 이벤트에서 코드 업데이트, PR, 저장소 생성 최대 12건을 표시한다. 전체 커밋·PR 이력이 아니며 API 반영에도 지연이 있다.
- 조회 실패: 로컬 또는 저장소의 `data/github.json`에 저장된 정보가 있으면 마지막 조회 날짜와 함께 유지한다. 처음부터 없는 항목을 0건으로 꾸미지 않는다. Actions에서 새로 가져온 정보는 그 실행의 사이트에만 반영되며 다음 실행용 파일로 저장하지 않는다.

배포 워크플로는 GitHub가 발급하는 `GITHUB_TOKEN`으로 잔디와 고정 저장소를 조회한다. 2026-11-17 첫 배포에서 두 조회가 성공했고, 공개 주소에서 기여 달력이 표시되는 것을 확인했다. 이후 조회가 실패하면 저장된 정보로 대체하거나 조회 불가 안내를 표시한다.

## GitHub Pages 연결

1. `owencity` 계정에 공개 저장소 `owencity.github.io`를 만든다.
2. 이 폴더의 소스를 저장소에 올린다. `node_modules`, `dist`, `qa`, 비밀값 파일은 제외한다.
3. 저장소의 **Settings → Pages → Build and deployment → Source**에서 **GitHub Actions**를 선택한다.
4. **Actions → Publish personal site → Run workflow**를 실행한다.
5. 배포 성공과 표시된 주소를 확인한다. 기본 주소는 `https://owencity.github.io`다.

이후 `main`에 올린 변경은 다시 배포된다. GitHub 정보 갱신을 위해 한국 시간 오전 7시 23분에 해당하는 예약도 설정했다. 예약은 지연·누락될 수 있고, 공개 저장소가 60일간 비활성 상태이면 중단될 수 있다. 필요하면 Actions에서 직접 실행한다.

## 구성

| 위치 | 수정할 내용 |
| --- | --- |
| `src/` | 홈과 글 상세의 HTML 구성 |
| `public/site.css`, `public/site.js` | 디자인, 검색, 메뉴, 테마 |
| `public/pixel-*.css`, `public/pixel-scenes.js`, `public/scene-player.js` | 픽셀 디자인, 풍경과 재생 조작 |
| `content/` | 글 본문과 목록 |
| `scripts/` | GitHub 조회, Markdown 변환, 미리보기 |
| `.github/workflows/pages.yml` | 빌드와 Pages 배포 |

Node.js 24에서 Markdown-it 15.0.3, Mermaid 11.17.2를 사용한다. 원래 티스토리 스킨의 Bluemiv 표기를 유지한다.

## 확인한 공식 자료

2026-11-17 확인. 아래 문서는 갱신될 수 있으며 이 사이트의 배포 성공을 뜻하지 않는다.

- [GitHub Pages 사용자 사이트](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages): 주소와 저장소 이름.
- [Pages 배포 워크플로](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages): 빌드·배포 구성.
- [GitHub GraphQL 사용자 정보](https://docs.github.com/en/graphql/reference/users): 고정 항목과 기여 달력.
- [공개 활동 API](https://docs.github.com/en/rest/activity/events): 이벤트 조회 범위와 지연.
- [GITHUB_TOKEN](https://docs.github.com/en/actions/concepts/security/github_token): 저장소에 발급되는 토큰의 범위.
- [예약 실행](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule): 지연과 비활성 상태에서의 중단.

/**
 * Helper for the shared sidebar.
 */
(function () {
    function setSidebarUser(username) {
        var displayName = username && username.trim().length > 0 ? username : 'Гость';
        var avatar = document.querySelector('[data-user-avatar]');
        var nameEl = document.querySelector('[data-user-name]');

        if (avatar) {
            var ch = displayName.charAt(0);
            avatar.textContent = ch ? ch.toUpperCase() : '?';
        }
        if (nameEl) {
            nameEl.textContent = displayName;
        }
    }

    async function refreshUserName(userId) {
        if (!userId) {
            return;
        }

        try {
            var headers = {};
            var token = localStorage.getItem('token');
            if (token) {
                headers.Authorization = 'Bearer ' + token;
            }
            var response = await fetch('/api/users/' + encodeURIComponent(userId) + '/profile', { headers: headers });

            if (!response.ok) {
                return;
            }

            var profile = await response.json();
            if (profile.username) {
                localStorage.setItem('username', profile.username);
                setSidebarUser(profile.username);
            }
        } catch (error) {
            console.warn('Не удалось обновить имя пользователя в сайдбаре', error);
        }
    }

    function init() {
        var userId = localStorage.getItem('userId');
        var username = localStorage.getItem('username');

        var navMap = {
            'home': '/home',
            'my-quizzes': '/my-quizzes',
            'history': '/history' + (userId ? '?userId=' + userId : ''),
            'profile': '/profile' + (userId ? '?userId=' + userId : ''),
            'edit-profile': '/edit-profile' + (userId ? '?userId=' + userId : ''),
            'create-quiz': '/quiz/create',
            'logout': '/logout'
        };

        document.querySelectorAll('[data-nav]').forEach(function (el) {
            var key = el.getAttribute('data-nav');
            if (navMap[key]) {
                el.setAttribute('href', navMap[key]);
            }
        });

        document.querySelectorAll('.user-card[data-nav="edit-profile"]').forEach(function (el) {
            el.setAttribute('aria-label', 'Профиль');
        });

        document.querySelectorAll('[data-nav="logout"]').forEach(function (el) {
            el.addEventListener('click', function () {
                localStorage.removeItem('token');
                localStorage.removeItem('userId');
                localStorage.removeItem('username');
            });
        });

        setSidebarUser(username);
        refreshUserName(userId);

        var activeKey = document.body.getAttribute('data-active-nav');
        if (activeKey) {
            var activeEl = document.querySelector('.sidebar [data-nav="' + activeKey + '"]');
            if (activeEl) {
                activeEl.classList.add('active');
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();

document.addEventListener('DOMContentLoaded', function() {
    const riotIdInput = document.getElementById('riotId');
    if (riotIdInput) {
        riotIdInput.focus();
    }
});

const matchHistoryCollapse = document.getElementById('matchHistoryCollapse');
if (matchHistoryCollapse) {
    const icon = matchHistoryCollapse.previousElementSibling.querySelector('.fa-chevron-down');
    if (icon) {
        matchHistoryCollapse.addEventListener('show.bs.collapse', function () {
            icon.classList.remove('fa-chevron-down');
            icon.classList.add('fa-chevron-up');
        });
        matchHistoryCollapse.addEventListener('hide.bs.collapse', function () {
            icon.classList.remove('fa-chevron-up');
            icon.classList.add('fa-chevron-down');
        });
    }
} 

const express = require('express');

const router = express.Router();

router.get('/', (req, res) => {
    const v = req.session.viewer;
    if (v && v.isAdmin) {
        return res.redirect('/admin');
    }
    if (v && v.creatorId) {
        return res.redirect('/me');
    }
    if (v) {
        return res.redirect('/apply');
    }
    res.render('landing', {
        title: null,
        signinFailed: req.query.signin === 'failed',
        signinClosed: req.query.signin === 'closed',
    });
});

module.exports = router;
